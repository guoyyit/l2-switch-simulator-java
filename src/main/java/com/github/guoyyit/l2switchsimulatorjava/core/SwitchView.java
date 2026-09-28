package com.github.guoyyit.l2switchsimulatorjava.core;

import java.util.List;

/**
 * 交换机运行状态快照。
 */
public record SwitchView(String name, long nowTick, int portCount,
                         List<PortView> ports, List<FdbEntryView> fdb) { }
