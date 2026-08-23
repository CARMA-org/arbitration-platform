package org.carma.arbitration.mechanism;

import org.carma.arbitration.model.Agent;
import org.carma.arbitration.model.ResourcePool;
import org.carma.arbitration.model.ResourceType;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class GroupingSplitterSpectralTest {

    private Set<Agent> sharedComputeAgents(int n) {
        Set<Agent> agents = new LinkedHashSet<>();
        for (int i = 0; i < n; i++) {
            Agent a = new Agent("S" + i, "Agent " + i, Map.of(ResourceType.COMPUTE, 1.0), 100);
            a.setRequest(ResourceType.COMPUTE, 1, 10);
            agents.add(a);
        }
        return agents;
    }

    private ResourcePool pool() {
        return new ResourcePool(Map.of(ResourceType.COMPUTE, 1000L));
    }

    private Map<String, Integer> membership(List<Set<Agent>> groups) {
        Map<String, Integer> m = new HashMap<>();
        for (int g = 0; g < groups.size(); g++) {
            for (Agent a : groups.get(g)) {
                m.put(a.getId(), g);
            }
        }
        return m;
    }

    @Test
    void spectralSplitCoversEveryAgentExactlyOnce() {
        GroupingSplitter splitter = new GroupingSplitter(
            new GroupingPolicy.Builder().maxGroupSize(4)
                .splitStrategy(GroupingPolicy.SplitStrategy.SPECTRAL).build());
        Set<Agent> agents = sharedComputeAgents(12);
        List<Set<Agent>> groups = splitter.splitBySpectral(agents, 4, pool());

        Set<String> seen = new HashSet<>();
        int total = 0;
        for (Set<Agent> group : groups) {
            for (Agent a : group) {
                assertTrue(seen.add(a.getId()), "agent in more than one group: " + a.getId());
                total++;
            }
        }
        assertEquals(12, total);
        assertEquals(12, seen.size());
    }

    @Test
    void spectralSplitRespectsMaximumSize() {
        GroupingSplitter splitter = new GroupingSplitter(
            new GroupingPolicy.Builder().maxGroupSize(4)
                .splitStrategy(GroupingPolicy.SplitStrategy.SPECTRAL).build());
        List<Set<Agent>> groups = splitter.splitBySpectral(sharedComputeAgents(12), 4, pool());
        for (Set<Agent> group : groups) {
            assertTrue(group.size() <= 4, "group exceeds max size: " + group.size());
        }
        assertTrue(groups.size() >= 3, "12 agents at max size 4 must form at least 3 groups");
    }

    @Test
    void spectralSplitIsRepeatable() {
        GroupingSplitter splitter = new GroupingSplitter(
            new GroupingPolicy.Builder().maxGroupSize(3)
                .splitStrategy(GroupingPolicy.SplitStrategy.SPECTRAL).build());
        Set<Agent> agents = sharedComputeAgents(15);
        Map<String, Integer> first = membership(splitter.splitBySpectral(agents, 3, pool()));
        Map<String, Integer> second = membership(splitter.splitBySpectral(agents, 3, pool()));
        assertEquals(first, second);
    }

    @Test
    void spectralPathRunsForSmallGroupsAndPartitionsDisjointly() {
        GroupingSplitter splitter = new GroupingSplitter(
            new GroupingPolicy.Builder().maxGroupSize(5)
                .splitStrategy(GroupingPolicy.SplitStrategy.SPECTRAL).build());
        Set<Agent> agents = sharedComputeAgents(20);
        List<Set<Agent>> groups = splitter.splitBySpectral(agents, 5, pool());
        int total = groups.stream().mapToInt(Set::size).sum();
        assertEquals(20, total);
        long distinct = groups.stream().flatMap(Set::stream).map(Agent::getId).distinct().count();
        assertEquals(20, distinct);
    }

    @Test
    void spectralIsAPublicSplitStrategyOption() {
        assertTrue(Arrays.asList(GroupingPolicy.SplitStrategy.values())
            .contains(GroupingPolicy.SplitStrategy.SPECTRAL));
    }
}
