package com.github.guoyyit.l2switchsimulatorjava.core;

import com.github.guoyyit.l2switchsimulatorjava.topology.Link;

import java.util.List;

/**
 * 整个仿真网络状态快照。
 */
public record NetworkView(long nowTick, List<SwitchView> switches,
                          List<Link> links, List<String> blockedPorts,
                          List<DropEventView> recentDrops) { }
