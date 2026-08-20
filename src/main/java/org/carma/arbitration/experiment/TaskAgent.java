package org.carma.arbitration.experiment;

import org.carma.arbitration.agent.RealisticAgentFramework.*;
import org.carma.arbitration.model.ResourceType;
import org.carma.arbitration.model.ServiceType;

import java.util.*;

public class TaskAgent extends RealisticAgent {

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
    private int mandatoryFailures;
    private int optionalRefinementsDone;
    private int optionalRefinementsPossible;
    private double qualitySum;
    private double sloAttainedSum;
    private final Map<ResourceType, Integer> exhaustedCounts = new EnumMap<>(ResourceType.class);

    private TaskAgent(Builder builder) {
        super(builder);
        this.tasks = builder.tasks;
    }

    private Map<ResourceType, Long> mandatoryBundle(Task task) {
        Map<ResourceType, Long> bundle = new EnumMap<>(ResourceType.class);
        for (ServiceType step : task.mandatory) {
            for (Map.Entry<ResourceType, Long> e : step.getDefaultResourceRequirements().entrySet()) {
                bundle.merge(e.getKey(), e.getValue(), Long::sum);
            }
        }
        return bundle;
    }

    private ResourceType firstUnaffordable(ExecutionContext context, Map<ResourceType, Long> bundle) {
        for (Map.Entry<ResourceType, Long> e : bundle.entrySet()) {
            if (context.getRemainingResource(e.getKey()) < e.getValue()) {
                return e.getKey();
            }
        }
        return null;
    }

    @Override
    protected GoalResult executeGoal(Goal goal, ExecutionContext context) {
        List<String> servicesUsed = new ArrayList<>();
        boolean[] mandatoryComplete = new boolean[tasks.size()];
        long[] mandatoryLatency = new long[tasks.size()];

        for (int t = 0; t < tasks.size(); t++) {
            Task task = tasks.get(t);
            ResourceType shortResource = firstUnaffordable(context, mandatoryBundle(task));
            if (shortResource != null) {
                mandatoryFailures++;
                exhaustedCounts.merge(shortResource, 1, Integer::sum);
                continue;
            }
            boolean completed = true;
            long latency = 0;
            for (ServiceType step : task.mandatory) {
                ServiceResult r = context.invokeService(step, Map.of("prompt", task.id));
                servicesUsed.add(step.name());
                if (!r.isSuccess()) {
                    completed = false;
                    if (r.getExhaustedResource() != null) {
                        exhaustedCounts.merge(r.getExhaustedResource(), 1, Integer::sum);
                    }
                    break;
                }
                latency += step.getBaseLatencyMs();
            }
            if (completed) {
                mandatoryComplete[t] = true;
                mandatoryLatency[t] = latency;
                tasksDone++;
            } else {
                mandatoryFailures++;
            }
        }

        for (int t = 0; t < tasks.size(); t++) {
            if (!mandatoryComplete[t]) {
                continue;
            }
            Task task = tasks.get(t);
            optionalRefinementsPossible += task.optional.size();
            int optionalDone = 0;
            long latency = mandatoryLatency[t];
            for (ServiceType step : task.optional) {
                ServiceResult r = context.invokeService(step, Map.of("prompt", task.id));
                servicesUsed.add(step.name());
                if (r.isSuccess()) {
                    optionalDone++;
                    optionalRefinementsDone++;
                    latency += step.getBaseLatencyMs();
                } else if (r.getExhaustedResource() != null) {
                    exhaustedCounts.merge(r.getExhaustedResource(), 1, Integer::sum);
                }
            }
            double optionalFraction = task.optional.isEmpty()
                ? 0.0 : (double) optionalDone / task.optional.size();
            double quality = Math.min(1.0, task.baseQuality + task.refinementBonus * optionalFraction);
            qualitySum += quality;
            if (latency <= task.sloMs) {
                sloAttainedSum += 1.0;
            }
        }

        boolean anyProgress = tasksDone > 0;
        return new GoalResult(anyProgress, "completed " + tasksDone + "/" + tasks.size() + " tasks",
            new HashMap<>(), 0, servicesUsed);
    }

    public int getTasksTotal() { return tasks.size(); }
    public int getTasksDone() { return tasksDone; }
    public int getMandatoryFailures() { return mandatoryFailures; }
    public int getOptionalRefinementsDone() { return optionalRefinementsDone; }
    public int getOptionalRefinementsPossible() { return optionalRefinementsPossible; }
    public double getCompletion() { return tasks.isEmpty() ? 0.0 : (double) tasksDone / tasks.size(); }
    public double getMeanQuality() { return tasks.isEmpty() ? 0.0 : qualitySum / tasks.size(); }
    public double getSloAttainment() { return tasks.isEmpty() ? 0.0 : sloAttainedSum / tasks.size(); }
    public double getOptionalRefinementRate() {
        return optionalRefinementsPossible == 0
            ? 0.0 : (double) optionalRefinementsDone / optionalRefinementsPossible;
    }
    public Map<ResourceType, Integer> getExhaustedCounts() { return exhaustedCounts; }

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
