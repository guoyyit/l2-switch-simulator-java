package com.github.guoyyit.l2switchsimulatorjava.sim.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;

/**
 * 交换机间链路与无环收敛。
 *
 * <p>环路防护采用确定性“生成树”：
 * 将交换机视为节点、交换机间链路视为无向边，按
 * （端点交换机名、端点端口号）的字典序对边排序，再以并查集按序加入边，
 * 加入后会成环的边即为阻断边，其两端端口都被阻断（主机接入端口永不参与、永不阻断）。
 * 相同拓扑下阻断结果逐次一致；链路增删后调用方应重新
 * {@link #recomputeBlocking} 收敛。
 */
public final class Topology {

    /** 链路的一个端点。 */
    public record Endpoint(String switchName, int portId) {
    }

    private record Edge(Endpoint a, Endpoint b) {
        /** 规范化：字典序小的端点放前面。 */
        Edge normalized() {
            return compare(a, b) <= 0 ? this : new Edge(b, a);
        }
    }

    private static int compare(Endpoint x, Endpoint y) {
        int c = x.switchName().compareTo(y.switchName());
        return c != 0 ? c : Integer.compare(x.portId(), y.portId());
    }

    private final List<Edge> edges = new ArrayList<>();
    private final Map<String, Endpoint> neighborByPort = new HashMap<>();

    public Topology() {
    }

    /** 增加一条链路；同一端口已有链路会被拒绝（每端口至多一条上联链路）。 */
    public void addLink(String switchA, int portA, String switchB, int portB) {
        Endpoint a = new Endpoint(switchA, portA);
        Endpoint b = new Endpoint(switchB, portB);
        String ka = key(a);
        String kb = key(b);
        if (neighborByPort.containsKey(ka) || neighborByPort.containsKey(kb)) {
            throw new IllegalStateException("端口已存在链路: " + ka + " / " + kb);
        }
        if (switchA.equals(switchB)) {
            throw new IllegalArgumentException("暂不支持同一交换机自环链路");
        }
        Edge e = new Edge(a, b).normalized();
        edges.add(e);
        neighborByPort.put(key(a), b);
        neighborByPort.put(key(b), a);
    }

    /** 清空全部链路（仿真重置时使用）。 */
    public void clear() {
        edges.clear();
        neighborByPort.clear();
    }

    public void removeLink(String switchA, int portA, String switchB, int portB) {
        Endpoint a = new Endpoint(switchA, portA);
        Endpoint b = new Endpoint(switchB, portB);
        Edge target = new Edge(a, b).normalized();
        boolean removed = edges.removeIf(e -> e.a.equals(target.a) && e.b.equals(target.b));
        if (removed) {
            neighborByPort.remove(key(a));
            neighborByPort.remove(key(b));
        }
    }

    public Optional<Endpoint> neighbor(String switchName, int portId) {
        return Optional.ofNullable(neighborByPort.get(key(new Endpoint(switchName, portId))));
    }

    /** 链路视图：[["switchA","portA","switchB","portB"], ...]，按确定性顺序。 */
    public List<List<String>> linksView() {
        List<Edge> sorted = new ArrayList<>(edges);
        sorted.sort(Comparator.comparing(Edge::a, Topology::compare).thenComparing(Edge::b, Topology::compare));
        List<List<String>> view = new ArrayList<>(sorted.size());
        for (Edge e : sorted) {
            view.add(List.of(e.a().switchName(), String.valueOf(e.a().portId()),
                    e.b().switchName(), String.valueOf(e.b().portId())));
        }
        return List.copyOf(view);
    }

    /**
     * 计算需要阻断的端口，返回 "switchName:portId" 列表（有序、去重）。
     *
     * @param hostFacing 判断 (switchName, portId) 是否为主机端口；主机端口永不阻断
     */
    public List<String> recomputeBlocking(BiPredicate<String, Integer> hostFacing) {
        List<Edge> sorted = new ArrayList<>(edges);
        sorted.sort(Comparator.comparing(Edge::a, Topology::compare).thenComparing(Edge::b, Topology::compare));

        // 并查集按交换机名（拓扑节点）
        Map<String, String> parent = new HashMap<>();
        List<String> blocked = new ArrayList<>();
        for (Edge e : sorted) {
            String na = e.a().switchName();
            String nb = e.b().switchName();
            parent.putIfAbsent(na, na);
            parent.putIfAbsent(nb, nb);
            String ra = find(parent, na);
            String rb = find(parent, nb);
            boolean createsLoop = ra.equals(rb);
            union(parent, ra, rb);
            if (createsLoop) {
                addBlocked(blocked, e.a(), hostFacing);
                addBlocked(blocked, e.b(), hostFacing);
            }
        }
        blocked.sort(String::compareTo);
        return List.copyOf(blocked);
    }

    private void addBlocked(List<String> blocked, Endpoint ep, BiPredicate<String, Integer> hostFacing) {
        if (hostFacing.test(ep.switchName(), ep.portId())) {
            return;
        }
        String k = key(ep);
        if (!blocked.contains(k)) {
            blocked.add(k);
        }
    }

    private static String find(Map<String, String> parent, String node) {
        String root = node;
        while (!parent.get(root).equals(root)) {
            root = parent.get(root);
        }
        String cur = node;
        while (!cur.equals(root)) {
            String next = parent.get(cur);
            parent.put(cur, root);
            cur = next;
        }
        return root;
    }

    private static void union(Map<String, String> parent, String ra, String rb) {
        if (ra.equals(rb)) {
            return;
        }
        if (ra.compareTo(rb) < 0) {
            parent.put(rb, ra);
        } else {
            parent.put(ra, rb);
        }
    }

    private static String key(Endpoint ep) {
        return ep.switchName() + ":" + ep.portId();
    }
}
