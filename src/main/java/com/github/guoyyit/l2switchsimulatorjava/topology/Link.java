package com.github.guoyyit.l2switchsimulatorjava.topology;

/**
 * 两台交换机之间的双向链路。规范化存储：字典序较小的交换机端为 A 端。
 */
public record Link(String switchA, int portA, String switchB, int portB) {

    public Link {
        if (switchA == null || switchB == null || switchA.equals(switchB)) {
            throw new IllegalArgumentException("link endpoints required and must differ");
        }
        if (switchA.compareTo(switchB) > 0) {
            String tmpName = switchA;
            int tmpPort = portA;
            switchA = switchB;
            portA = portB;
            switchB = tmpName;
            portB = tmpPort;
        }
    }

    /** 返回链路另一端；若本链路不包含该端点返回 null。 */
    public Endpoint other(String switchName, int portIndex) {
        if (switchA.equals(switchName) && portA == portIndex) {
            return new Endpoint(switchB, portB);
        }
        if (switchB.equals(switchName) && portB == portIndex) {
            return new Endpoint(switchA, portA);
        }
        return null;
    }

    public boolean touches(String switchName) {
        return switchA.equals(switchName) || switchB.equals(switchName);
    }

    /** 链路确定性键，用于生成树选择。 */
    public String key() {
        return switchA + ":" + portA + "<->" + switchB + ":" + portB;
    }

    public record Endpoint(String switchName, int portIndex) { }
}
