package io.agenticshortener;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Two-phase, approval-gated SDLC workflow. The supplied repository is never edited. */
final class WorkflowCli {
    private WorkflowCli() {}

    static void run(String[] args) throws Exception {
        if (args.length < 2) { usage(); return; }
        String action = args[1];
        if ("metrics".equals(action)) { metrics(); return; }
        Map<String, String> options = options(args, 2);
        switch (action) {
            case "plan" -> plan(options);
            case "apply" -> apply(options);
            case "finalize" -> finalizeRun(options);
            case "reject" -> reject(options);
            default -> usage();
        }
    }

    private static void plan(Map<String, String> options) throws Exception {
        String requirement = required(options, "requirement");
        String scenario = options.getOrDefault("scenario", "brownfield").toLowerCase();
        if (!List.of("greenfield", "brownfield", "ambiguous").contains(scenario)) {
            throw new IllegalArgumentException("scenario must be greenfield, brownfield, or ambiguous");
        }
        Path root = Path.of(required(options, "repo")).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("Repository directory does not exist: " + root);
        Store store = new Store();
        String id = UUID.randomUUID().toString();
        Path runDir = store.runDir(id);
        Files.createDirectories(runDir);
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("id", id);
        run.put("createdAt", Instant.now().toString());
        run.put("startedAt", Instant.now().toString());
        run.put("scenario", scenario);
        run.put("requirement", requirement);
        run.put("repoRoot", root.toString());
        run.put("repoFingerprint", RepositorySupport.fingerprint(root));
        run.put("status", "PLANNING");
        run.put("validationAttempts", 0);
        run.put("repairAttempts", 0);
        run.put("rollbackCount", 0);
        String supersedes = options.get("supersedes");
        if (supersedes != null) run.put("supersedes", supersedes);
        store.save(run);
        store.event(id, "RUN_STARTED", Map.of("scenario", scenario, "requirementSha256", sha256(requirement)));
        if (supersedes != null) {
            try {
                Map<String, Object> previous = store.load(supersedes);
                previous.put("status", "SUPERSEDED");
                previous.put("supersededBy", id);
                previous.put("updatedAt", Instant.now().toString());
                store.save(previous);
                store.event(supersedes, "REPLANNED", Map.of("newRunId", id, "approvalsInvalidated", true));
            } catch (Exception ex) {
                throw new IllegalArgumentException("Could not mark superseded run " + supersedes + ": " + ex.getMessage());
            }
        }

        try {
            Map<String, Object> repo = RepositorySupport.inspect(root);
            AgentBackend agent = AgentBackend.fromEnvironment();
            run.put("model", agent.model());

            Map<String, Object> requirementInput = Map.of("requirement", requirement, "scenario", scenario,
                    "repositoryInventory", repo.get("files"),
                    "task", "Normalize the requirement; identify ambiguity and write measurable acceptance criteria.");
            Map<String, Object> interpretation = agent.generate("requirements", requirementInput);
            recordStage(store, run, "requirements", requirementInput, interpretation);
            run.put("requirementInterpretation", interpretation);
            List<String> questions = strings(interpretation.get("clarifications"));
            if ("ambiguous".equals(scenario) && questions.isEmpty()) {
                questions = List.of("Please specify the missing behavior, constraints, and acceptance criteria before implementation.");
            }
            if (!questions.isEmpty()) {
                run.put("clarifications", questions);
                run.put("status", "NEEDS_CLARIFICATION");
                store.save(run);
                store.event(id, "SAFE_STOP_CLARIFICATION", Map.of("questions", questions));
                writeText(runDir.resolve("clarification.md"), "# Clarification required\n\n" + String.join("\n", questions.stream().map(q -> "- " + q).toList()));
                printRun(store, run, "Workflow safely stopped for clarification. No source changes were applied.");
                return;
            }

            Map<String, Object> context = Map.of("requirement", requirement, "scenario", scenario,
                    "interpretation", interpretation, "repositoryEvidence", repo);
            CompletableFuture<Map<String, Object>> impactFuture = CompletableFuture.supplyAsync(() -> call(agent, "codebase-impact", context));
            CompletableFuture<Map<String, Object>> designFuture = CompletableFuture.supplyAsync(() -> call(agent, "architecture-design", context));
            Map<String, Object> impact = impactFuture.join();
            Map<String, Object> design = designFuture.join();
            recordStage(store, run, "codebase-impact", context, impact);
            recordStage(store, run, "architecture-design", context, design);
            run.put("impactAnalysis", impact);
            run.put("architectureDecision", design);

            Map<String, Object> taskInput = Map.of("requirement", interpretation, "impactAnalysis", impact,
                    "architectureDecision", design, "repositoryFiles", repo.get("files"));
            Map<String, Object> tasks = agent.generate("task-decomposition", taskInput);
            recordStage(store, run, "task-decomposition", taskInput, tasks);
            run.put("taskPlan", tasks);

            Map<String, Object> implementationInput = Map.of("requirement", interpretation, "impactAnalysis", impact,
                    "architectureDecision", design, "taskPlan", tasks, "repositoryEvidence", repo,
                    "outputContract", "Return changes as path-to-complete-file-content; include deletions, risks and testPlan.");
            Map<String, Object> implementation = agent.generate("implementation-proposal", implementationInput);
            recordStage(store, run, "implementation-proposal", implementationInput, implementation);
            Map<String, String> changes = RepositorySupport.validatedChanges(implementation.get("changes"));
            List<String> deletions = RepositorySupport.validatedDeletions(implementation.get("deletions"));
            if (changes.isEmpty() && deletions.isEmpty()) throw new IllegalArgumentException("Agent produced no source changes; safe-stop instead of claiming completion.");
            run.put("implementationProposal", implementation);
            run.put("changedFiles", allChangedPaths(changes, deletions));
            Path preview = runDir.resolve("plan-preview");
            RepositorySupport.copyTree(root, preview);
            RepositorySupport.apply(preview, changes, deletions);
            String previewDiff = RepositorySupport.makeDiff(root, preview, allChangedPaths(changes, deletions));
            writeText(runDir.resolve("proposal-diff.patch"), previewDiff);
            deleteTree(preview);
            run.put("proposalDiff", "proposal-diff.patch");
            run.put("status", "AWAITING_PLAN_APPROVAL");
            run.put("updatedAt", Instant.now().toString());
            store.save(run);
            writeText(runDir.resolve("proposal.md"), renderProposal(run));
            store.event(id, "PLAN_READY_FOR_APPROVAL", Map.of("changedFiles", allChangedPaths(changes, deletions),
                    "proposalDiff", "proposal-diff.patch", "approvalRequired", true));
            printRun(store, run, "Review proposal.md, risks, test plan, and changed files before running apply --approve-plan.");
        } catch (Throwable error) {
            String message = rootMessage(error);
            run.put("status", "MANUAL_REVIEW_REQUIRED");
            run.put("safeStopReason", message);
            run.put("updatedAt", Instant.now().toString());
            store.save(run);
            store.event(id, "SAFE_STOP_AGENT_OR_POLICY_FAILURE", Map.of("reason", message));
            writeText(runDir.resolve("safe-stop.md"), "# Safe stop\n\n" + message + "\n\nNo source changes were applied. Review the audit and rerun after resolving the issue.");
            printRun(store, run, "Agent or policy step failed. The workflow is incomplete and not release-ready.");
            throw new IllegalStateException("Workflow safe-stopped: " + message);
        }
    }

    private static void apply(Map<String, String> options) throws Exception {
        String id = required(options, "run");
        Store store = new Store();
        Map<String, Object> run = store.load(id);
        if (!"AWAITING_PLAN_APPROVAL".equals(run.get("status"))) throw new IllegalStateException("Run is not waiting for plan approval.");
        if (!options.containsKey("approve-plan")) throw new IllegalStateException("Explicit approval required: add --approve-plan after reviewing proposal.md.");
        String repoHash = RepositorySupport.fingerprint(Path.of(String.valueOf(run.get("repoRoot"))));
        if (!repoHash.equals(run.get("repoFingerprint"))) {
            run.put("status", "STALE_PLAN_REQUIRES_REPLAN");
            run.put("updatedAt", Instant.now().toString());
            store.save(run);
            store.event(id, "STALE_PLAN_BLOCKED", Map.of("approvalsInvalidated", true));
            throw new IllegalStateException("Repository changed after planning. Generate a new plan; the old approval cannot be reused.");
        }
        Path runDir = store.runDir(id);
        Path sandbox = runDir.resolve("sandbox");
        Path baseline = sandbox.resolve("baseline");
        Path working = sandbox.resolve("working");
        RepositorySupport.copyTree(Path.of(String.valueOf(run.get("repoRoot"))), baseline);
        RepositorySupport.copyTree(baseline, working);
        run.put("planApprovedAt", Instant.now().toString());
        run.put("sandbox", working.toString());
        run.put("status", "IMPLEMENTING_IN_SANDBOX");
        store.save(run);
        store.event(id, "PLAN_APPROVED", Map.of("approval", "explicit CLI flag", "sandbox", working.toString()));

        Map<String, Object> implementation = map(run.get("implementationProposal"));
        Map<String, String> changes = RepositorySupport.validatedChanges(implementation.get("changes"));
        List<String> deletions = RepositorySupport.validatedDeletions(implementation.get("deletions"));
        RepositorySupport.apply(working, changes, deletions);
        List<String> changedFiles = allChangedPaths(changes, deletions);
        writeText(runDir.resolve("diff.patch"), RepositorySupport.makeDiff(baseline, working, changedFiles));
        store.event(id, "SANDBOX_CHANGES_APPLIED", Map.of("changedFiles", changedFiles, "sourceRepositoryUnchanged", true));

        List<String> command = RepositorySupport.buildCommand(working);
        if (command.isEmpty()) {
            run.put("status", "MANUAL_REVIEW_REQUIRED");
            run.put("validationEvidence", "No supported test command was found; release readiness is blocked.");
            run.put("updatedAt", Instant.now().toString());
            store.save(run);
            store.event(id, "VALIDATION_BLOCKED_NO_TEST_COMMAND", Map.of("releaseReady", false));
            printRun(store, run, "No build/test command found. The sandbox diff is preserved for manual review; release readiness is blocked.");
            return;
        }
        run.put("testCommand", command);
        store.save(run);
        AgentBackend agent;
        try { agent = AgentBackend.fromEnvironment(); }
        catch (Exception ex) { safeRollback(store, run, ex.getMessage()); return; }

        for (int attempt = 1; attempt <= 3; attempt++) {
            Path log = runDir.resolve("validation-attempt-" + attempt + ".log");
            RepositorySupport.ProcessResult result;
            Instant validationStartedAt = Instant.now();
            try {
                result = RepositorySupport.execute(command, working, log, runDir.resolve("isolated-home"));
            } catch (Exception ex) {
                safeRollback(store, run, "Validation could not start: " + ex.getMessage());
                return;
            }
            int validations = intValue(run.get("validationAttempts")) + 1;
            run.put("validationAttempts", validations);
            Map<String, Object> validationRecord = Map.of("attempt", attempt, "command", command, "exitCode", result.exitCode(),
                    "timedOut", result.timedOut(), "elapsedMs", result.elapsedMs(), "log", log.toString(),
                    "output", result.output(), "startedAt", validationStartedAt.toString(), "completedAt", Instant.now().toString());
            run.put("lastValidation", validationRecord);
            List<Map<String, Object>> validationHistory = mapList(run.get("validationHistory"));
            validationHistory.add(validationRecord);
            run.put("validationHistory", validationHistory);
            run.put("updatedAt", Instant.now().toString());
            store.save(run);
            store.event(id, result.exitCode() == 0 ? "VALIDATION_PASSED" : "VALIDATION_FAILED",
                    Map.of("attempt", attempt, "exitCode", result.exitCode(), "elapsedMs", result.elapsedMs(), "log", log.toString()));
            if (result.exitCode() == 0 && !result.timedOut()) {
                validationPassed(store, run, agent, baseline, working, changedFiles, result);
                return;
            }
            if (attempt == 3) break;
            try {
                Map<String, Object> repairInput = Map.of("requirement", run.get("requirement"),
                        "previousImplementation", implementation,
                        "changedFiles", changedFiles,
                        "currentChangedSource", changedSource(working, changedFiles),
                        "validationFailure", Map.of("command", command, "exitCode", result.exitCode(), "timedOut", result.timedOut(), "output", result.output()),
                        "attempt", attempt, "maxRepairAttempts", 2,
                        "instruction", "Diagnose this actual failure and return only a minimal corrected changes object and deletions.");
                Map<String, Object> repair = agent.generate("failure-driven-repair", repairInput);
                recordStage(store, run, "repair-" + attempt, repairInput, repair);
                Map<String, String> repairChanges = RepositorySupport.validatedChanges(repair.get("changes"));
                List<String> repairDeletions = RepositorySupport.validatedDeletions(repair.get("deletions"));
                if (repairChanges.isEmpty() && repairDeletions.isEmpty()) throw new IllegalArgumentException("Repair agent returned no changes.");
                RepositorySupport.apply(working, repairChanges, repairDeletions);
                for (String path : allChangedPaths(repairChanges, repairDeletions)) if (!changedFiles.contains(path)) changedFiles.add(path);
                implementation = repair;
                run.put("implementationProposal", repair);
                run.put("changedFiles", changedFiles);
                run.put("repairAttempts", intValue(run.get("repairAttempts")) + 1);
                writeText(runDir.resolve("diff.patch"), RepositorySupport.makeDiff(baseline, working, changedFiles));
                store.save(run);
                store.event(id, "REPAIR_APPLIED", Map.of("repairAttempt", attempt, "changedFiles", changedFiles));
            } catch (Exception ex) {
                safeRollback(store, run, "Repair could not produce a safe correction: " + rootMessage(ex));
                return;
            }
        }
        safeRollback(store, run, "Validation failed after two bounded repair attempts. See validation logs; no release approval is available.");
    }

    private static void validationPassed(Store store, Map<String, Object> run, AgentBackend agent, Path baseline,
                                        Path working, List<String> changedFiles, RepositorySupport.ProcessResult validation) throws Exception {
        String source = changedSource(working, changedFiles);
        Map<String, Object> docsInput = Map.of("requirement", run.get("requirement"), "scenario", run.get("scenario"),
                "finalChangedSource", source,
                "validation", Map.of("command", run.get("testCommand"), "exitCode", validation.exitCode(), "elapsedMs", validation.elapsedMs()),
                "task", "Write an accurate final engineering summary. Include changed components, API behavior, test result, risks, rollback steps, limitations and setup. Do not claim deployment.");
        try {
            Map<String, Object> documentation = agent.generate("final-documentation", docsInput);
            recordStage(store, run, "final-documentation", docsInput, documentation);
            String summary = documentation.get("summary") instanceof String text ? text : Json.stringify(documentation);
            writeText(store.runDir(String.valueOf(run.get("id"))).resolve("final-engineering-summary.md"), summary);
            run.put("finalDocumentation", summary);
        } catch (Exception ex) {
            run.put("status", "MANUAL_REVIEW_REQUIRED");
            run.put("safeStopReason", "Validation passed but final documentation generation failed; approval is blocked.");
            store.save(run);
            store.event(String.valueOf(run.get("id")), "DOCUMENTATION_SAFE_STOP", Map.of("releaseReady", false));
            return;
        }
        run.put("status", "AWAITING_RELEASE_APPROVAL");
        run.put("updatedAt", Instant.now().toString());
        run.put("repoFingerprintAfterValidation", RepositorySupport.fingerprint(Path.of(String.valueOf(run.get("repoRoot")))));
        store.save(run);
        store.event(String.valueOf(run.get("id")), "VALIDATED_AWAITING_RELEASE_APPROVAL",
                Map.of("changedFiles", changedFiles, "validationAttempts", run.get("validationAttempts"), "approvalRequired", true));
        writeText(store.runDir(String.valueOf(run.get("id"))).resolve("diff.patch"), RepositorySupport.makeDiff(baseline, working, changedFiles));
        printRun(store, run, "Validation passed. Review diff.patch, logs, residual risks, and final summary before finalize --approve-release. No deployment occurred.");
    }

    private static void finalizeRun(Map<String, String> options) throws Exception {
        String id = required(options, "run");
        Store store = new Store();
        Map<String, Object> run = store.load(id);
        if (!"AWAITING_RELEASE_APPROVAL".equals(run.get("status"))) throw new IllegalStateException("Run is not awaiting release approval; no release can be marked ready.");
        if (!options.containsKey("approve-release")) throw new IllegalStateException("Explicit release approval required: add --approve-release after reviewing all evidence.");
        String before = String.valueOf(run.get("repoFingerprint"));
        String now = RepositorySupport.fingerprint(Path.of(String.valueOf(run.get("repoRoot"))));
        if (!before.equals(now)) {
            run.put("status", "STALE_PLAN_REQUIRES_REPLAN");
            store.save(run);
            store.event(id, "RELEASE_APPROVAL_BLOCKED_REPOSITORY_CHANGED", Map.of("approvalsInvalidated", true));
            throw new IllegalStateException("Source repository changed during the run. Replan; release approval is invalidated.");
        }
        run.put("releaseApprovedAt", Instant.now().toString());
        run.put("status", "READY_FOR_REVIEW");
        run.put("updatedAt", Instant.now().toString());
        run.put("deployment", "NOT_PERFORMED");
        run.put("metrics", runMetrics(run));
        store.save(run);
        store.event(id, "RELEASE_APPROVED_FOR_REVIEW", Map.of("deploymentPerformed", false, "changedFiles", run.get("changedFiles")));
        writeText(store.runDir(id).resolve("final-review.md"), renderFinal(run));
        printRun(store, run, "Outcome is ready for human review in the isolated sandbox. This prototype does not deploy or promote to production.");
    }

    private static void reject(Map<String, String> options) throws Exception {
        String id = required(options, "run");
        String reason = options.getOrDefault("reason", "Rejected by reviewer");
        Store store = new Store();
        Map<String, Object> run = store.load(id);
        if (List.of("READY_FOR_REVIEW", "ROLLED_BACK", "SUPERSEDED").contains(run.get("status"))) {
            throw new IllegalStateException("This run cannot be rejected in its current state.");
        }
        safeRollback(store, run, reason);
        printRun(store, run, "Run rejected and its isolated working copy removed. Audit evidence is retained.");
    }

    private static void safeRollback(Store store, Map<String, Object> run, String reason) throws Exception {
        String id = String.valueOf(run.get("id"));
        Path root = Path.of(String.valueOf(run.get("repoRoot")));
        String original = String.valueOf(run.get("repoFingerprint"));
        Path sandbox = store.runDir(id).resolve("sandbox");
        deleteTree(sandbox);
        String after = RepositorySupport.fingerprint(root);
        boolean restored = original.equals(after);
        run.put("status", restored ? "ROLLED_BACK" : "SAFE_STOP_RESTORE_CHECK_FAILED");
        run.put("rollbackCount", intValue(run.get("rollbackCount")) + 1);
        run.put("rollbackEvidence", Map.of("reason", reason, "originalFingerprint", original, "finalFingerprint", after,
                "sourceRestored", restored, "sandboxRemoved", !Files.exists(sandbox), "at", Instant.now().toString()));
        run.put("updatedAt", Instant.now().toString());
        run.put("safeStopReason", reason);
        run.put("metrics", runMetrics(run));
        store.save(run);
        store.event(id, "ROLLBACK", map(run.get("rollbackEvidence")));
        writeText(store.runDir(id).resolve("rollback-evidence.json"), Json.stringify(run.get("rollbackEvidence")));
    }

    private static void metrics() throws Exception {
        Store store = new Store();
        List<Map<String, Object>> runs = store.allRuns();
        int complete = 0, rolledBack = 0, retries = 0, validations = 0, recoveredRuns = 0;
        long totalLatency = 0, recoveryLatency = 0;
        for (Map<String, Object> run : runs) {
            String status = String.valueOf(run.get("status"));
            if ("READY_FOR_REVIEW".equals(status)) complete++;
            if ("ROLLED_BACK".equals(status) || "SAFE_STOP_RESTORE_CHECK_FAILED".equals(status)) rolledBack++;
            retries += intValue(run.get("repairAttempts"));
            validations += intValue(run.get("validationAttempts"));
            Map<String, Object> metrics = map(run.get("metrics"));
            if (Boolean.TRUE.equals(metrics.get("recoveredAfterFailure"))) {
                recoveredRuns++;
                recoveryLatency += longValue(metrics.get("timeToRecoveryMs"));
            }
            Object started = run.get("startedAt");
            Object updated = run.get("updatedAt");
            if (started instanceof String s && updated instanceof String u) {
                try { totalLatency += Duration.between(Instant.parse(s), Instant.parse(u)).toMillis(); }
                catch (Exception ignored) { /* incomplete record */ }
            }
        }
        int count = runs.size();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runs", count);
        result.put("completedReviewRuns", complete);
        result.put("engineeringRunSuccessRate", count == 0 ? 0.0 : (double) complete / count);
        result.put("validationExecutions", validations);
        result.put("repairAttempts", retries);
        result.put("rollbackRuns", rolledBack);
        result.put("rollbackFrequency", count == 0 ? 0.0 : (double) rolledBack / count);
        result.put("averageEndToEndLatencyMs", count == 0 ? 0 : totalLatency / count);
        result.put("source", "persisted run records and validation/audit events");
        result.put("recoveredRuns", recoveredRuns);
        result.put("meanTimeToRecoveryMs", recoveredRuns == 0 ? 0 : recoveryLatency / recoveredRuns);
        result.put("note", "Success means a run reached READY_FOR_REVIEW; no production deployment is performed.");
        System.out.println(Json.stringify(result));
    }

    private static void recordStage(Store store, Map<String, Object> run, String stage,
                                    Object input, Map<String, Object> output) throws Exception {
        String id = String.valueOf(run.get("id"));
        Map<String, Object> stages = run.get("stages") instanceof Map<?, ?> existing ? map(existing) : new LinkedHashMap<>();
        stages.put(stage, Map.of("inputSha256", sha256(Json.stringify(input)), "output", output,
                "artifactRef", "stage-" + stage + ".json", "completedAt", Instant.now().toString()));
        run.put("stages", stages);
        store.save(run);
        writeText(store.runDir(id).resolve("stage-" + safeName(stage) + ".json"), Json.stringify(output));
        store.event(id, "STAGE_COMPLETED", Map.of("stage", stage, "inputSha256", sha256(Json.stringify(input)), "artifactRef", "stage-" + safeName(stage) + ".json"));
    }

    private static Map<String, Object> call(AgentBackend agent, String stage, Object context) {
        try { return agent.generate(stage, context); }
        catch (Exception ex) { throw new CompletionException(ex); }
    }

    private static String renderProposal(Map<String, Object> run) {
        Map<String, Object> interpretation = map(run.get("requirementInterpretation"));
        Map<String, Object> design = map(run.get("architectureDecision"));
        Map<String, Object> impact = map(run.get("impactAnalysis"));
        Map<String, Object> implementation = map(run.get("implementationProposal"));
        return "# Proposed engineering change\n\n" +
                "Run: `" + run.get("id") + "`  \nScenario: `" + run.get("scenario") + "`  \n" +
                "Status: `AWAITING_PLAN_APPROVAL`\n\n" +
                "## Requirement\n\n" + run.get("requirement") + "\n\n" +
                "## Interpretation and acceptance criteria\n\n" + Json.stringify(interpretation) + "\n\n" +
                "## Repository impact\n\n" + Json.stringify(impact) + "\n\n" +
                "## Architecture decision\n\n" + Json.stringify(design) + "\n\n" +
                "## Tasks\n\n" + Json.stringify(run.get("taskPlan")) + "\n\n" +
                "## Proposed files\n\n" + String.join("\n", strings(run.get("changedFiles")).stream().map(p -> "- `" + p + "`").toList()) + "\n\n" +
                "Exact proposed unified diff: `proposal-diff.patch`\n\n" +
                "## Risks and test plan\n\n" + Json.stringify(Map.of("risks", implementation.getOrDefault("risks", List.of()),
                        "testPlan", implementation.getOrDefault("testPlan", "Run the repository's detected test command."))) + "\n\n" +
                "Review this plan before explicitly approving it. Approval applies changes only to an isolated copy.\n";
    }

    private static String renderFinal(Map<String, Object> run) {
        return "# Final engineering review\n\n" +
                "Run: `" + run.get("id") + "`  \nScenario: `" + run.get("scenario") + "`  \n" +
                "Status: `READY_FOR_REVIEW`  \nDeployment: `NOT_PERFORMED`\n\n" +
                "## Requirement\n\n" + run.get("requirement") + "\n\n" +
                "## Changed files\n\n" + String.join("\n", strings(run.get("changedFiles")).stream().map(p -> "- `" + p + "`").toList()) + "\n\n" +
                "## Validation evidence\n\n" + Json.stringify(run.get("lastValidation")) + "\n\n" +
                "## Metrics\n\n" + Json.stringify(run.get("metrics")) + "\n\n" +
                "## Final summary\n\n" + run.get("finalDocumentation") + "\n\n" +
                "Diff: `diff.patch`  \nAudit trail: `audit.jsonl`  \nSandbox: `sandbox/working`\n";
    }

    private static Map<String, Object> runMetrics(Map<String, Object> run) {
        Instant start = Instant.parse(String.valueOf(run.get("startedAt")));
        Instant now = Instant.now();
        long timeToRecovery = 0;
        Instant firstFailure = null;
        for (Map<String, Object> attempt : mapList(run.get("validationHistory"))) {
            try {
                if (intValue(attempt.get("exitCode")) != 0 && firstFailure == null) {
                    firstFailure = Instant.parse(String.valueOf(attempt.get("completedAt")));
                } else if (intValue(attempt.get("exitCode")) == 0 && firstFailure != null) {
                    timeToRecovery = Duration.between(firstFailure, Instant.parse(String.valueOf(attempt.get("completedAt")))).toMillis();
                    break;
                }
            } catch (Exception ignored) { /* incomplete validation evidence */ }
        }
        if (timeToRecovery == 0 && firstFailure != null) {
            Map<String, Object> rollback = map(run.get("rollbackEvidence"));
            if (Boolean.TRUE.equals(rollback.get("sourceRestored"))) {
                try { timeToRecovery = Duration.between(firstFailure, Instant.parse(String.valueOf(rollback.get("at")))).toMillis(); }
                catch (Exception ignored) { /* incomplete rollback evidence */ }
            }
        }
        return Map.of("endToEndLatencyMs", Duration.between(start, now).toMillis(),
                "validationExecutions", intValue(run.get("validationAttempts")),
                "repairAttempts", intValue(run.get("repairAttempts")),
                "rollbackCount", intValue(run.get("rollbackCount")),
                "recoveredAfterFailure", timeToRecovery > 0,
                "timeToRecoveryMs", timeToRecovery);
    }

    private static List<String> allChangedPaths(Map<String, String> changes, List<String> deletions) {
        List<String> paths = new ArrayList<>(changes.keySet());
        for (String deletion : deletions) if (!paths.contains(deletion)) paths.add(deletion);
        return paths;
    }

    private static String changedSource(Path root, List<String> paths) throws IOException {
        StringBuilder out = new StringBuilder();
        int remaining = 100_000;
        for (String path : paths) {
            Path file = root.resolve(path);
            if (!Files.isRegularFile(file)) continue;
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.length() > remaining) content = content.substring(0, Math.max(0, remaining)) + "\n[truncated]";
            if (content.isEmpty()) continue;
            out.append("\n--- ").append(path).append(" ---\n").append(content).append('\n');
            remaining -= content.length();
            if (remaining <= 0) break;
        }
        return out.toString();
    }

    private static void printRun(Store store, Map<String, Object> run, String message) {
        System.out.println("Run ID: " + run.get("id"));
        System.out.println("Status: " + run.get("status"));
        System.out.println("Artifacts: " + store.runDir(String.valueOf(run.get("id"))));
        System.out.println(message);
    }

    private static Map<String, String> options(String[] args, int start) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = start; i < args.length; i++) {
            if (!args[i].startsWith("--")) throw new IllegalArgumentException("Expected an option, got " + args[i]);
            String key = args[i].substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) result.put(key, args[++i]);
            else result.put(key, "true");
        }
        return result;
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank() || "true".equals(value)) throw new IllegalArgumentException("Missing required --" + key);
        return value;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    private static List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) return new ArrayList<>();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) if (item instanceof Map<?, ?> map) result.add(map(map));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static int intValue(Object value) { return value instanceof Number n ? n.intValue() : 0; }
    private static long longValue(Object value) { return value instanceof Number n ? n.longValue() : 0L; }
    private static String safeName(String text) { return text.replaceAll("[^A-Za-z0-9._-]", "_"); }
    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    private static void writeText(Path path, String text) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }
    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
    private static void usage() {
        System.out.println("""
                Workflow commands:
                  workflow plan --repo PATH --requirement TEXT [--scenario greenfield|brownfield|ambiguous] [--supersedes RUN_ID]
                  workflow apply --run RUN_ID --approve-plan
                  workflow finalize --run RUN_ID --approve-release
                  workflow reject --run RUN_ID --reason TEXT
                  workflow metrics
                Each phase writes artifacts beneath ~/.agentic-url-shortener/runs. No phase deploys.
                """);
    }

    private static final class Store {
        private final Path root = Path.of(System.getenv().getOrDefault("AGENTIC_RUNS_DIR",
                Path.of(System.getProperty("user.home"), ".agentic-url-shortener", "runs").toString())).toAbsolutePath().normalize();
        private Store() throws IOException { Files.createDirectories(root); }
        private Path runDir(String id) {
            if (!id.matches("[A-Za-z0-9-]{8,64}")) throw new IllegalArgumentException("Invalid run ID");
            return root.resolve(id);
        }
        private void save(Map<String, Object> run) throws IOException {
            Path dir = runDir(String.valueOf(run.get("id")));
            Files.createDirectories(dir);
            writeText(dir.resolve("run.json"), Json.stringify(run));
        }
        private Map<String, Object> load(String id) throws IOException {
            Path file = runDir(id).resolve("run.json");
            if (!Files.isRegularFile(file)) throw new IllegalArgumentException("Unknown run ID: " + id);
            return Json.object(Files.readString(file, StandardCharsets.UTF_8));
        }
        private void event(String id, String type, Map<String, Object> details) throws IOException {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("timestamp", Instant.now().toString());
            event.put("runId", id);
            event.put("type", type);
            event.put("details", details);
            Files.writeString(runDir(id).resolve("audit.jsonl"), Json.stringify(event) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        private List<Map<String, Object>> allRuns() throws IOException {
            List<Map<String, Object>> result = new ArrayList<>();
            try (var paths = Files.list(root)) {
                for (Path dir : paths.filter(Files::isDirectory).toList()) {
                    Path file = dir.resolve("run.json");
                    if (Files.isRegularFile(file)) {
                        try { result.add(Json.object(Files.readString(file, StandardCharsets.UTF_8))); }
                        catch (Exception ignored) { /* incomplete record is not counted */ }
                    }
                }
            }
            return result;
        }
    }
}
