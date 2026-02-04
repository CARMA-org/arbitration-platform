package org.carma.arbitration.config.factories;

import org.carma.arbitration.agent.RealisticAgentFramework.*;
import org.carma.arbitration.agent.KotlinScriptExecutor;
import org.carma.arbitration.config.AgentConfigLoader.*;
import org.carma.arbitration.config.AgentTypeRegistry;
import org.carma.arbitration.config.AgentTypeRegistry.BaseAgentFactory;
import org.carma.arbitration.config.ConfigurableAgent;
import org.carma.arbitration.model.*;

import java.time.Duration;
import java.util.*;

/**
 * Factory for creating scripted agents from YAML configuration.
 *
 * This factory handles agents that use Kotlin scripting via:
 * - initialization: block (runs once at agent creation)
 * - execution: block (runs for each goal execution)
 *
 * It bridges the gap between YAML configuration and the ConfigurableAgent class.
 */
public class ScriptedAgentFactory extends BaseAgentFactory {

    @Override
    public RealisticAgent create(AgentConfig config, Map<String, OutputChannel> channels,
                                 KotlinScriptExecutor executor) {
        ConfigurableAgent.Builder builder = new ConfigurableAgent.Builder(config.id)
            .name(config.name)
            .description(config.description)
            .autonomyLevel(AutonomyLevel.valueOf(config.autonomy))
            .initialCurrency(config.currency)
            .config(config);

        // Set scripts
        if (config.initialization != null) {
            builder.initializationScript(config.initialization);
        }
        if (config.execution != null) {
            builder.executionScript(config.execution);
        }
        // Legacy support: if behavior is set but execution is not
        if (config.behavior != null && config.execution == null) {
            builder.executionScript(config.behavior);
        }

        // Set required services from config
        if (config.services != null && config.services.required != null) {
            for (String svc : config.services.required) {
                builder.requireService(ServiceType.valueOf(svc));
            }
        }
        // Also check legacy parameters.requiredServices
        if (config.parameters != null) {
            @SuppressWarnings("unchecked")
            List<String> legacyServices = (List<String>) config.parameters.get("requiredServices");
            if (legacyServices != null) {
                for (String svc : legacyServices) {
                    builder.requireService(ServiceType.valueOf(svc));
                }
            }
        }

        // Set operating domains
        if (config.domains != null) {
            builder.operatingDomains(new HashSet<>(config.domains));
        }
        // Also check legacy parameters.operatingDomains
        if (config.parameters != null) {
            @SuppressWarnings("unchecked")
            List<String> legacyDomains = (List<String>) config.parameters.get("operatingDomains");
            if (legacyDomains != null) {
                builder.operatingDomains(new HashSet<>(legacyDomains));
            }
        }

        // Set resource preferences
        if (config.preferences != null) {
            for (Map.Entry<String, Double> entry : config.preferences.entrySet()) {
                ResourceType type = ResourceType.valueOf(entry.getKey());
                builder.resourcePreference(type, entry.getValue());
            }
        }

        // Add goals from config
        if (config.goals != null) {
            for (GoalConfig goalConfig : config.goals) {
                Goal.GoalType goalType = Goal.GoalType.valueOf(goalConfig.type);
                Duration period = goalConfig.period != null ? Duration.parse(goalConfig.period) : null;

                Goal goal = new Goal(
                    goalConfig.id,
                    goalConfig.description,
                    goalType,
                    period,
                    null,  // deadline
                    goalConfig.priority
                );

                if (goalConfig.parameters != null) {
                    for (Map.Entry<String, Object> param : goalConfig.parameters.entrySet()) {
                        goal.setParameter(param.getKey(), param.getValue());
                    }
                }

                builder.addGoal(goal);
            }
        }

        // Add output channels
        addOutputChannels(builder, config, channels);

        return builder.build();
    }

    /**
     * Register this factory with the AgentTypeRegistry.
     */
    public static void register() {
        AgentTypeRegistry registry = AgentTypeRegistry.getInstance();
        registry.setScriptedAgentFactory(new ScriptedAgentFactory());

        // Also register for explicit "scripted" type
        registry.register("scripted", new ScriptedAgentFactory());
    }
}
