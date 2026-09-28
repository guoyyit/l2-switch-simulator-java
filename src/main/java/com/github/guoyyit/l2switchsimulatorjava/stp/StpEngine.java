package com.github.guoyyit.l2switchsimulatorjava.stp;

import com.github.guoyyit.l2switchsimulatorjava.topology.Link;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;

/**
 * 简化 STP：在环路上按确定规则阻断端口，收敛为无环生成树。
 *
 * 收敛规则（全部确定性，无随机量）：
 * 1. 根桥为名称字典序最小的交换机；
 * 2. 从根做 BFS，候选链路按 key（"A:pA<->B:pB"）字典序选择，得到生成树边；
 * 3. 生成树边两端的端口保持转发；每条非树边只阻断名称字典序较大一端的端口；
 * 4. 结果为 BlockedPort 的有序集合，相同拓扑逐次计算结果一致。
 */
public final class StpEngine {

    /** 返回当前应被阻断的端口集合。 */
    public Set<BlockedPort> recompute(Iterable<String> switches, Iterable<Link> links) {
        List<String> nodes = new ArrayList<>();
        switches.forEach(nodes::add);
        List<Link> allLinks = new ArrayList<>();
        links.forEach(allLinks::add);
        nodes.sort(Comparator.naturalOrder());
        allLinks.sort(Comparator.comparing(Link::key));

        Set<BlockedPort> blocked = new TreeSet<>();
        if (nodes.isEmpty()) {
            return blocked;
        }

        Set<String> visited = new HashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        String root = nodes.get(0);
        visited.add(root);
        queue.add(root);

        Set<Link> treeEdges = new HashSet<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (Link link : allLinks) {
                String neighbor = null;
                if (link.switchA().equals(current) && !visited.contains(link.switchB())) {
                    neighbor = link.switchB();
                } else if (link.switchB().equals(current) && !visited.contains(link.switchA())) {
                    neighbor = link.switchA();
                }
                if (neighbor != null) {
                    visited.add(neighbor);
                    treeEdges.add(link);
                    queue.add(neighbor);
                }
            }
        }

        for (Link link : allLinks) {
            if (!treeEdges.contains(link)) {
                // 规范化后 B 端名称字典序较大，阻断 B 端。
                blocked.add(new BlockedPort(link.switchB(), link.portB()));
            }
        }
        return blocked;
    }

    public record BlockedPort(String switchName, int portIndex) implements Comparable<BlockedPort> {
        @Override
        public int compareTo(BlockedPort o) {
            int c = switchName.compareTo(o.switchName);
            return c != 0 ? c : Integer.compare(portIndex, o.portIndex);
        }

        @Override
        public String toString() {
            return switchName + ":" + portIndex;
        }
    }
}
