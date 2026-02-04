package org.carma.arbitration.config.factories;

import org.carma.arbitration.agent.RealisticAgentFramework.*;
import org.carma.arbitration.agent.ExampleAgents.*;
import org.carma.arbitration.agent.KotlinScriptExecutor;
import org.carma.arbitration.config.AgentConfigLoader.*;
import org.carma.arbitration.config.AgentTypeRegistry;
import org.carma.arbitration.config.AgentTypeRegistry.BaseAgentFactory;

import java.time.Duration;
import java.util.*;

/**
 * Factory implementations for all built-in agent types.
 * These enable backward compatibility with existing YAML configurations.
 */
public class BuiltInAgentFactories {

    /**
     * Factory for NewsSearchAgent.
     */
    public static class NewsSearchAgentFactory extends BaseAgentFactory {
        @Override
        public RealisticAgent create(AgentConfig config, Map<String, OutputChannel> channels,
                                     KotlinScriptExecutor executor) {
            NewsSearchAgent.Builder builder = new NewsSearchAgent.Builder(config.id)
                .name(config.name)
                .description(config.description)
                .initialCurrency(config.currency);

            List<String> topics = getParamList(config.parameters, "topics");
            if (topics != null) {
                builder.topics(topics);
            }

            Integer maxResults = getParamInt(config.parameters, "maxResultsPerSearch");
            if (maxResults != null) {
                builder.maxResultsPerSearch(maxResults);
            }

            String format = getParamString(config.parameters, "summaryFormat");
            if (format != null) {
                builder.summaryFormat(format);
            }

            String periodStr = getParamString(config.parameters, "searchPeriod");
            if (periodStr != null) {
                builder.searchPeriod(Duration.parse(periodStr));
            }

            addOutputChannels(builder, config, channels);
            return builder.build();
        }
    }

    /**
     * Factory for DocumentSummarizerAgent.
     */
    public static class DocumentSummarizerAgentFactory extends BaseAgentFactory {
        @Override
        public RealisticAgent create(AgentConfig config, Map<String, OutputChannel> channels,
                                     KotlinScriptExecutor executor) {
            DocumentSummarizerAgent.Builder builder = new DocumentSummarizerAgent.Builder(config.id)
                .name(config.name)
                .description(config.description)
                .initialCurrency(config.currency);

            Integer maxLength = getParamInt(config.parameters, "maxDocumentLength");
            if (maxLength != null) {
                builder.maxDocumentLength(maxLength);
            }

            String format = getParamString(config.parameters, "outputFormat");
            if (format != null) {
                builder.outputFormat(format);
            }

            addOutputChannels(builder, config, channels);
            return builder.build();
        }
    }

    /**
     * Factory for DataExtractionAgent.
     */
    public static class DataExtractionAgentFactory extends BaseAgentFactory {
        @Override
        public RealisticAgent create(AgentConfig config, Map<String, OutputChannel> channels,
                                     KotlinScriptExecutor executor) {
            DataExtractionAgent.Builder builder = new DataExtractionAgent.Builder(config.id)
                .name(config.name)
                .description(config.description)
                .initialCurrency(config.currency);

            String dataSource = getParamString(config.parameters, "dataSource");
            if (dataSource != null) {
                builder.dataSource(dataSource);
            }

            String schema = getParamString(config.parameters, "extractionSchema");
            if (schema != null) {
                builder.extractionSchema(schema);
            }

            List<String> fields = getParamList(config.parameters, "fieldsToExtract");
            if (fields != null) {
                builder.fieldsToExtract(fields);
            }

            String periodStr = getParamString(config.parameters, "extractionPeriod");
            if (periodStr != null) {
                builder.extractionPeriod(Duration.parse(periodStr));
            }

            addOutputChannels(builder, config, channels);
            return builder.build();
        }
    }

    /**
     * Factory for ResearchAssistantAgent.
     */
    public static class ResearchAssistantAgentFactory extends BaseAgentFactory {
        @Override
        public RealisticAgent create(AgentConfig config, Map<String, OutputChannel> channels,
                                     KotlinScriptExecutor executor) {
            ResearchAssistantAgent.Builder builder = new ResearchAssistantAgent.Builder(config.id)
                .name(config.name)
                .description(config.description)
                .initialCurrency(config.currency);

            List<String> domains = getParamList(config.parameters, "researchDomains");
            if (domains != null) {
                builder.researchDomains(domains);
            }

            Integer maxSources = getParamInt(config.parameters, "maxSourcesPerQuery");
            if (maxSources != null) {
                builder.maxSourcesPerQuery(maxSources);
            }

            Boolean cite = (Boolean) config.parameters.get("citeSources");
            if (cite != null) {
                builder.citeSources(cite);
            }

            addOutputChannels(builder, config, channels);
            return builder.build();
        }
    }

    /**
     * Factory for CodeReviewAgent.
     */
    public static class CodeReviewAgentFactory extends BaseAgentFactory {
        @Override
        public RealisticAgent create(AgentConfig config, Map<String, OutputChannel> channels,
                                     KotlinScriptExecutor executor) {
            CodeReviewAgent.Builder builder = new CodeReviewAgent.Builder(config.id)
                .name(config.name)
                .description(config.description)
                .initialCurrency(config.currency);

            List<String> languages = getParamList(config.parameters, "languagesSupported");
            if (languages != null) {
                builder.languagesSupported(languages);
            }

            List<String> categories = getParamList(config.parameters, "checkCategories");
            if (categories != null) {
                builder.checkCategories(categories);
            }

            addOutputChannels(builder, config, channels);
            return builder.build();
        }
    }

    /**
     * Factory for MonitoringAgent.
     */
    public static class MonitoringAgentFactory extends BaseAgentFactory {
        @Override
        @SuppressWarnings("unchecked")
        public RealisticAgent create(AgentConfig config, Map<String, OutputChannel> channels,
                                     KotlinScriptExecutor executor) {
            MonitoringAgent.Builder builder = new MonitoringAgent.Builder(config.id)
                .name(config.name)
                .description(config.description)
                .initialCurrency(config.currency);

            Map<String, Object> thresholds = (Map<String, Object>) config.parameters.get("thresholds");
            if (thresholds != null) {
                for (Map.Entry<String, Object> entry : thresholds.entrySet()) {
                    builder.threshold(entry.getKey(), ((Number) entry.getValue()).doubleValue());
                }
            }

            String alertChannel = getParamString(config.parameters, "alertChannel");
            if (alertChannel != null) {
                builder.alertChannel(alertChannel);
            }

            String periodStr = getParamString(config.parameters, "checkPeriod");
            if (periodStr != null) {
                builder.checkPeriod(Duration.parse(periodStr));
            }

            addOutputChannels(builder, config, channels);
            return builder.build();
        }
    }
}
