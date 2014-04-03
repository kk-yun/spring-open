package net.onrc.onos.core.intent.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.floodlightcontroller.util.MACAddress;
import net.onrc.onos.core.intent.FlowEntry;
import net.onrc.onos.core.intent.Intent;
import net.onrc.onos.core.intent.IntentOperation;
import net.onrc.onos.core.intent.IntentOperation.Operator;
import net.onrc.onos.core.intent.IntentOperationList;
import net.onrc.onos.core.intent.PathIntent;
import net.onrc.onos.core.intent.ShortestPathIntent;
import net.onrc.onos.core.topology.LinkEvent;
//import net.onrc.onos.core.topology.NetworkGraph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Brian O'Connor <bocon@onlab.us>
 */

public class PlanCalcRuntime {

    //    NetworkGraph graph;
    private final static Logger log = LoggerFactory.getLogger(PlanCalcRuntime.class);

    public PlanCalcRuntime(/*NetworkGraph graph*/) {
//      this.graph = graph;
    }

    public List<Set<FlowEntry>> computePlan(IntentOperationList intentOps) {
        long start = System.nanoTime();
        List<Collection<FlowEntry>> flowEntries = computeFlowEntries(intentOps);
        long step1 = System.nanoTime();
        List<Set<FlowEntry>> plan = buildPhases(flowEntries);
        long step2 = System.nanoTime();
        log.error("MEASUREMENT: Compute flow entries: {} ns, Build phases: {} ns",
                (step1 - start), (step2 - step1));
        return plan;
    }

    private List<Collection<FlowEntry>> computeFlowEntries(IntentOperationList intentOps) {
        List<Collection<FlowEntry>> flowEntries = new LinkedList<>();
        for (IntentOperation i : intentOps) {
            if (!(i.intent instanceof PathIntent)) {
                log.warn("Not a path intent: {}", i);
                continue;
            }
            PathIntent intent = (PathIntent) i.intent;
            Intent parent = intent.getParentIntent();
            long srcPort, dstPort;
            long lastDstSw = -1, lastDstPort = -1;
            MACAddress srcMac, dstMac;
            if (parent instanceof ShortestPathIntent) {
                ShortestPathIntent pathIntent = (ShortestPathIntent) parent;
//              Switch srcSwitch = graph.getSwitch(pathIntent.getSrcSwitchDpid());
//              srcPort = srcSwitch.getPort(pathIntent.getSrcPortNumber());
                srcPort = pathIntent.getSrcPortNumber();
                srcMac = MACAddress.valueOf(pathIntent.getSrcMac());
                dstMac = MACAddress.valueOf(pathIntent.getDstMac());
//              Switch dstSwitch = graph.getSwitch(pathIntent.getDstSwitchDpid());
                lastDstSw = pathIntent.getDstSwitchDpid();
//              lastDstPort = dstSwitch.getPort(pathIntent.getDstPortNumber());
                lastDstPort = pathIntent.getDstPortNumber();
            } else {
                log.warn("Unsupported Intent: {}", parent);
                continue;
            }
            List<FlowEntry> entries = new ArrayList<>();
            for (LinkEvent linkEvent : intent.getPath()) {
//              Link link = graph.getLink(linkEvent.getSrc().getDpid(),
//                        linkEvent.getSrc().getNumber(),
//                        linkEvent.getDst().getDpid(),
//                        linkEvent.getDst().getNumber());
//              Switch sw = link.getSrcSwitch();
                long sw = linkEvent.getSrc().getDpid();
//              dstPort = link.getSrcPort();
                dstPort = linkEvent.getSrc().getNumber();
                FlowEntry fe = new FlowEntry(sw, srcPort, dstPort, srcMac, dstMac, i.operator);
                entries.add(fe);
//              srcPort = link.getDstPort();
                srcPort = linkEvent.getDst().getNumber();
            }
            if (lastDstSw >= 0 && lastDstPort >= 0) {
                //Switch sw = lastDstPort.getSwitch();
                long sw = lastDstSw;
                dstPort = lastDstPort;
                FlowEntry fe = new FlowEntry(sw, srcPort, dstPort, srcMac, dstMac, i.operator);
                entries.add(fe);
            }
            // install flow entries in reverse order
            Collections.reverse(entries);
            flowEntries.add(entries);
        }
        return flowEntries;
    }

    private List<Set<FlowEntry>> buildPhases(List<Collection<FlowEntry>> flowEntries) {
        Map<FlowEntry, Integer> map = new HashMap<>();
        List<Set<FlowEntry>> plan = new ArrayList<>();
        for (Collection<FlowEntry> c : flowEntries) {
            for (FlowEntry e : c) {
                Integer i = map.get(e);
                if (i == null) {
                    i = Integer.valueOf(0);
                }
                switch (e.getOperator()) {
                    case ADD:
                        i += 1;
                        break;
                    case REMOVE:
                        i -= 1;
                        break;
                    default:
                        break;
                }
                map.put(e, i);
                // System.out.println(e + " " + e.getOperator());
            }
        }

        // really simple first iteration of plan
        //TODO: optimize the map in phases
        Set<FlowEntry> phase = new HashSet<>();
        for (FlowEntry e : map.keySet()) {
            Integer i = map.get(e);
            if (i == 0) {
                continue;
            } else if (i > 0) {
                e.setOperator(Operator.ADD);
            } else if (i < 0) {
                e.setOperator(Operator.REMOVE);
            }
            phase.add(e);
        }
        plan.add(phase);

        return plan;
    }
}