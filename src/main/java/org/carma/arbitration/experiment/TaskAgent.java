package org.carma.arbitration.experiment;

import org.carma.arbitration.agent.RealisticAgentFramework.*;
import org.carma.arbitration.model.ResourceType;
import org.carma.arbitration.model.ServiceType;

import java.util.*;

/**
 * A concrete agent that executes a fixed, externally supplied queue of tasks
 * through the canonical constrained-execution path. Task completion, quality and
 * SLO attainment are defined by the task specification, not by any allocator
 * objective, and are computed here from the results of the enforced service
 * calls. The allocator never observes these outcomes.
 */
public class TaskAgent extends RealisticAgent {

    /** One task: mandatory steps must all succeed to complete; optional steps refine quality. */
    public static final class Task {
        final String id;
        final List<ServiceType> mandatory;
        final List<ServiceType> optional;
        final double baseQuality;
        final double refinementBonus;
        final long sloMs;

        public Task(String id, List<ServiceType> mandatory, List<ServiceType> optional,
                    double baseQuality, double refinementBonus, long sloMs) {
            this.id = id;
            this.mandatory = mandatory;
            this.optional = optional;
            this.baseQuality = baseQuality;
            this.refinementBonus = refinementBonus;
            this.sloMs = sloMs;
        }
    }

    private final List<Task> tasks;
    private int tasksDone;
    private double qualitySum;
    private double sloAttainedSum;

    private TaskAgent(Builder builder) {
        super(builder);
        this.tasks = builder.tasks;
    }

    @Override
    protected GoalResult executeGoal(Goal goal, ExecutionContext context) {
        List<String> servicesUsed = new ArrayList<>();
        for (Task task : tasks) {
            boolean completed = true;
            long latency = 0;
            for (ServiceType step : task.mandatory) {
                ServiceResult r = context.invokeService(step, Map.of("prompt", task.id));
                servicesUsed.add(step.name());
                if (!r.isSuccess()) {
                    completed = false;
                    break;
                }
                latency += step.getBaseLatencyMs();
            }
            if (!completed) {
                continue; // task failed; quality 0, SLO not attained
            }
            int optionalDone = 0;
            for (ServiceType step : task.optional) {
                ServiceResult r = context.invokeService(step, Map.of("prompt", task.id));
                servicesUsed.add(step.name());
                if (r.isSuccess()) {
                    optionalDone++;
                    latency += step.getBaseLatencyMs();
                }
            }
            double optionalFraction = task.optional.isEmpty()
                ? 0.0 : (double) optionalDone / task.optional.size();
            double quality = Math.min(1.0, task.baseQuality + task.refinementBonus * optionalFraction);
            tasksDone++;
            qualitySum += quality;
            if (latency <= task.sloMs) {
                sloAttainedSum += 1.0;
            }
        }
        return GoalResult.success("executed " + tasks.size() + " tasks", new HashMap<>(), 0, servicesUsed);
    }

    public int getTasksTotal() { return tasks.size(); }
    public int getTasksDone() { return tasksDone; }
    public double getCompletion() { return tasks.isEmpty() ? 0.0 : (double) tasksDone / tasks.size(); }
    public double getMeanQuality() { return tasks.isEmpty() ? 0.0 : qualitySum / tasks.size(); }
    public double getSloAttainment() { return tasks.isEmpty() ? 0.0 : sloAttainedSum / tasks.size(); }

    @Override
    public Set<ServiceType> getRequiredServiceTypes() {
        Set<ServiceType> types = new HashSet<>();
        for (Task t : tasks) {
            types.addAll(t.mandatory);
            types.addAll(t.optional);
        }
        return types;
    }

    @Override
    public Set<String> getOperatingDomains() {
        return Set.of("experiment");
    }

    public static class Builder extends RealisticAgent.Builder<Builder> {
        private final List<Task> tasks = new ArrayList<>();

        public Builder(String agentId) {
            super(agentId);
            this.autonomyLevel = AutonomyLevel.TOOL;
        }

        public Builder tasks(List<Task> t) {
            this.tasks.addAll(t);
            return this;
        }

        public Builder preferences(Map<ResourceType, Double> prefs) {
            this.resourcePreferences.putAll(prefs);
            return this;
        }

        @Override
        public TaskAgent build() {
            Goal goal = new Goal("run-all", "execute task queue", Goal.GoalType.ONE_TIME);
            this.goals.add(goal);
            return new TaskAgent(this);
        }
    }
}
