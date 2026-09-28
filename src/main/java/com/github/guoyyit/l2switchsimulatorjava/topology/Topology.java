package com.github.guoyyit.l2switchsimulatorjava.topology;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 交换机互联拓扑：节点与无向链路集合，供 STP 做环路检测。
 * 非线程安全，由 Network 在统一锁内维护。
 */
public final class Topology {

    private final Set<String> switches = new LinkedHashSet<>();
    private final List<Link> links = new ArrayList<>();

    public void addSwitch(String name) {
        switches.add(name);
    }

    public void addLink(Link link) {
        if (!switches.contains(link.switchA()) || !switches.contains(link.switchB())) {
            throw new IllegalArgumentException("link endpoints must be added first: " + link.key());
        }
        for (Link existing : links) {
            if (samePort(existing.switchA(), existing.portA(), link.switchA(), link.portA())
                    || samePort(existing.switchA(), existing.portA(), link.switchB(), link.portB())
                    || samePort(existing.switchB(), existing.portB(), link.switchA(), link.portA())
                    || samePort(existing.switchB(), existing.portB(), link.switchB(), link.portB())) {
                throw new IllegalArgumentException("port already in use: " + link.key());
            }
        }
        links.add(link);
    }

    private static boolean samePort(String s1, int p1, String s2, int p2) {
        return s1.equals(s2) && p1 == p2;
    }

    public void removeLink(Link link) {
        links.removeIf(l -> l.equals(link));
    }

    public Set<String> switches() {
        return new LinkedHashSet<>(switches);
    }

    public List<Link> links() {
        return new ArrayList<>(links);
    }

    public List<Link> linksOf(String switchName) {
        List<Link> result = new ArrayList<>();
        for (Link link : links) {
            if (link.touches(switchName)) {
                result.add(link);
            }
        }
        return result;
    }
}
