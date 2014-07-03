package net.onrc.onos.core.topology;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.util.SingletonTask;
import net.floodlightcontroller.threadpool.IThreadPoolService;
import net.onrc.onos.core.devicemanager.IOnosDeviceListener;
import net.onrc.onos.core.devicemanager.IOnosDeviceService;
import net.onrc.onos.core.devicemanager.OnosDevice;
import net.onrc.onos.core.linkdiscovery.ILinkDiscoveryListener;
import net.onrc.onos.core.linkdiscovery.ILinkDiscoveryService;
import net.onrc.onos.core.main.IOFSwitchPortListener;
import net.onrc.onos.core.registry.IControllerRegistryService;
import net.onrc.onos.core.registry.IControllerRegistryService.ControlChangeCallback;
import net.onrc.onos.core.registry.RegistryException;
import net.onrc.onos.core.util.Dpid;
import net.onrc.onos.core.util.PortNumber;
import net.onrc.onos.core.util.SwitchPort;

import org.openflow.protocol.OFPhysicalPort;
import org.openflow.util.HexString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The TopologyPublisher subscribes to topology network events from the
 * discovery modules. These events are reformatted and relayed to the in-memory
 * topology instance.
 */
public class TopologyPublisher implements /*IOFSwitchListener,*/
        IOFSwitchPortListener,
        ILinkDiscoveryListener,
        IFloodlightModule,
        IOnosDeviceListener {
    private static final Logger log =
            LoggerFactory.getLogger(TopologyPublisher.class);

    private IFloodlightProviderService floodlightProvider;
    private ILinkDiscoveryService linkDiscovery;
    private IControllerRegistryService registryService;
    private ITopologyService topologyService;

    private IOnosDeviceService onosDeviceService;

    private Topology topology;
    private TopologyDiscoveryInterface topologyDiscoveryInterface;

    private static final String ENABLE_CLEANUP_PROPERTY = "EnableCleanup";
    private boolean cleanupEnabled = true;
    private static final int CLEANUP_TASK_INTERVAL = 60; // in seconds
    private SingletonTask cleanupTask;

    /**
     * Cleanup old switches from the topology. Old switches are those
     * which have no controller in the registry.
     */
    private class SwitchCleanup implements ControlChangeCallback, Runnable {
        @Override
        public void run() {
            String old = Thread.currentThread().getName();
            Thread.currentThread().setName("SwitchCleanup@" + old);

            try {
                if (log.isTraceEnabled()) {
                    log.trace("Running cleanup thread");
                }
                switchCleanup();
            } finally {
                cleanupTask.reschedule(CLEANUP_TASK_INTERVAL,
                        TimeUnit.SECONDS);
                Thread.currentThread().setName(old);
            }
        }

        /**
         * First half of the switch cleanup operation. This method will attempt
         * to get control of any switch it sees without a controller via the
         * registry.
         */
        private void switchCleanup() {
            Iterable<Switch> switches = topology.getSwitches();

            if (log.isTraceEnabled()) {
                log.trace("Checking for inactive switches");
            }
            // For each switch check if a controller exists in controller registry
            for (Switch sw : switches) {
                try {
                    String controller =
                            registryService.getControllerForSwitch(sw.getDpid().value());
                    if (controller == null) {
                        log.debug("Requesting control to set switch {} INACTIVE",
                                sw.getDpid());
                        registryService.requestControl(sw.getDpid().value(), this);
                    }
                } catch (RegistryException e) {
                    log.error("Caught RegistryException in cleanup thread", e);
                }
            }
        }

        /**
         * Second half of the switch cleanup operation. If the registry grants
         * control of a switch, we can be sure no other instance is writing
         * this switch to the topology, so we can remove it now.
         *
         * @param dpid       the dpid of the switch we requested control for
         * @param hasControl whether we got control or not
         */
        @Override
        public void controlChanged(long dpid, boolean hasControl) {
            if (hasControl) {
                log.debug("Got control to set switch {} INACTIVE",
                        HexString.toHexString(dpid));

                SwitchEvent switchEvent = new SwitchEvent(new Dpid(dpid));
                topologyDiscoveryInterface.
                        removeSwitchDiscoveryEvent(switchEvent);
                registryService.releaseControl(dpid);
            }
        }
    }

    @Override
    public void linkDiscoveryUpdate(LDUpdate update) {

        LinkEvent linkEvent = new LinkEvent(
                        new SwitchPort(update.getSrc(), update.getSrcPort()),
                        new SwitchPort(update.getDst(), update.getDstPort()));
        // FIXME should be merging, with existing attrs, etc..
        // TODO define attr name as constant somewhere.
        // TODO populate appropriate attributes.
        linkEvent.freeze();

        switch (update.getOperation()) {
            case LINK_ADDED:
                if (!registryService.hasControl(update.getDst())) {
                    // Don't process or send a link event if we're not master for the
                    // destination switch
                    log.debug("Not the master for dst switch {}. Suppressed link add event {}.",
                            update.getDst(), linkEvent);
                    return;
                }
                topologyDiscoveryInterface.putLinkDiscoveryEvent(linkEvent);
                break;
            case LINK_UPDATED:
                // We don't use the LINK_UPDATED event (unsure what it means)
                break;
            case LINK_REMOVED:
                if (!registryService.hasControl(update.getDst())) {
                    // Don't process or send a link event if we're not master for the
                    // destination switch
                    log.debug("Not the master for dst switch {}. Suppressed link remove event {}.",
                            update.getDst(), linkEvent);
                    return;
                }
                topologyDiscoveryInterface.removeLinkDiscoveryEvent(linkEvent);
                break;
            default:
                break;
        }
    }

    @Override
    public void switchPortAdded(Long switchId, OFPhysicalPort port) {
        final Dpid dpid = new Dpid(switchId);
        PortEvent portEvent = new PortEvent(dpid, new PortNumber(port.getPortNumber()));
        // FIXME should be merging, with existing attrs, etc..
        // TODO define attr name as constant somewhere.
        // TODO populate appropriate attributes.
        portEvent.createStringAttribute("name", port.getName());

        portEvent.freeze();

        if (registryService.hasControl(switchId)) {
            topologyDiscoveryInterface.putPortDiscoveryEvent(portEvent);
            linkDiscovery.removeFromSuppressLLDPs(switchId, port.getPortNumber());
        } else {
            log.debug("Not the master for switch {}. Suppressed port add event {}.",
                    new Dpid(switchId), portEvent);
        }
    }

    @Override
    public void switchPortRemoved(Long switchId, OFPhysicalPort port) {
        final Dpid dpid = new Dpid(switchId);

        PortEvent portEvent = new PortEvent(dpid, new PortNumber(port.getPortNumber()));
        if (registryService.hasControl(switchId)) {
            topologyDiscoveryInterface.removePortDiscoveryEvent(portEvent);
        } else {
            log.debug("Not the master for switch {}. Suppressed port del event {}.",
                    dpid, portEvent);
        }
    }

    @Override
    public void addedSwitch(IOFSwitch sw) {
        final Dpid dpid = new Dpid(sw.getId());
        SwitchEvent switchEvent = new SwitchEvent(dpid);
        // FIXME should be merging, with existing attrs, etc..
        // TODO define attr name as constant somewhere.
        // TODO populate appropriate attributes.
        switchEvent.createStringAttribute("ConnectedSince",
                sw.getConnectedSince().toString());

        switchEvent.freeze();

        // TODO Not very robust
        if (!registryService.hasControl(sw.getId())) {
            log.debug("Not the master for switch {}. Suppressed switch add event {}.",
                    dpid, switchEvent);
            return;
        }

        List<PortEvent> portEvents = new ArrayList<PortEvent>();
        for (OFPhysicalPort port : sw.getPorts()) {
            PortEvent portEvent = new PortEvent(dpid, new PortNumber(port.getPortNumber()));
            // FIXME should be merging, with existing attrs, etc..
            // TODO define attr name as constant somewhere.
            // TODO populate appropriate attributes.
            portEvent.createStringAttribute("name", port.getName());

            portEvent.freeze();
            portEvents.add(portEvent);
        }
        topologyDiscoveryInterface
                .putSwitchDiscoveryEvent(switchEvent, portEvents);

        for (OFPhysicalPort port : sw.getPorts()) {
            // Allow links to be discovered on this port now that it's
            // in the database
            linkDiscovery.removeFromSuppressLLDPs(sw.getId(), port.getPortNumber());
        }
    }

    @Override
    public void removedSwitch(IOFSwitch sw) {
        // We don't use this event - switch remove is done by cleanup thread
    }

    @Override
    public void switchPortChanged(Long switchId) {
        // We don't use this event
    }

    @Override
    public String getName() {
        // TODO Auto-generated method stub
        return null;
    }

    /* *****************
     * IFloodlightModule
     * *****************/

    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleServices() {
        return null;
    }

    @Override
    public Map<Class<? extends IFloodlightService>, IFloodlightService>
    getServiceImpls() {
        return null;
    }

    @Override
    public Collection<Class<? extends IFloodlightService>>
    getModuleDependencies() {
        Collection<Class<? extends IFloodlightService>> l =
                new ArrayList<Class<? extends IFloodlightService>>();
        l.add(IFloodlightProviderService.class);
        l.add(ILinkDiscoveryService.class);
        l.add(IThreadPoolService.class);
        l.add(IControllerRegistryService.class);
        l.add(ITopologyService.class);
        l.add(IOnosDeviceService.class);
        return l;
    }

    @Override
    public void init(FloodlightModuleContext context)
            throws FloodlightModuleException {
        floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
        linkDiscovery = context.getServiceImpl(ILinkDiscoveryService.class);
        registryService = context.getServiceImpl(IControllerRegistryService.class);
        onosDeviceService = context.getServiceImpl(IOnosDeviceService.class);

        topologyService = context.getServiceImpl(ITopologyService.class);
    }

    @Override
    public void startUp(FloodlightModuleContext context) {
        floodlightProvider.addOFSwitchListener(this);
        linkDiscovery.addListener(this);
        onosDeviceService.addOnosDeviceListener(this);

        topology = topologyService.getTopology();
        topologyDiscoveryInterface =
                topologyService.getTopologyDiscoveryInterface();

        // Run the cleanup thread
        String enableCleanup =
                context.getConfigParams(this).get(ENABLE_CLEANUP_PROPERTY);
        if (enableCleanup != null
                && enableCleanup.equalsIgnoreCase("false")) {
            cleanupEnabled = false;
        }

        log.debug("Cleanup thread is {}enabled", (cleanupEnabled) ? "" : "not ");

        if (cleanupEnabled) {
            IThreadPoolService threadPool =
                    context.getServiceImpl(IThreadPoolService.class);
            cleanupTask = new SingletonTask(threadPool.getScheduledExecutor(),
                    new SwitchCleanup());
            // Run the cleanup task immediately on startup
            cleanupTask.reschedule(0, TimeUnit.SECONDS);
        }
    }

    @Override
    public void onosDeviceAdded(OnosDevice device) {
        log.debug("Called onosDeviceAdded mac {}", device.getMacAddress());

        SwitchPort sp = new SwitchPort(device.getSwitchDPID(), device.getSwitchPort());
        List<SwitchPort> spLists = new ArrayList<SwitchPort>();
        spLists.add(sp);
        DeviceEvent event = new DeviceEvent(device.getMacAddress());
        event.setAttachmentPoints(spLists);
        event.setLastSeenTime(device.getLastSeenTimestamp().getTime());
        // Does not use vlan info now.
        event.freeze();

        topologyDiscoveryInterface.putDeviceDiscoveryEvent(event);
    }

    @Override
    public void onosDeviceRemoved(OnosDevice device) {
        log.debug("Called onosDeviceRemoved");
        DeviceEvent event = new DeviceEvent(device.getMacAddress());
        // XXX shouldn't we be setting attachment points?
        event.freeze();
        topologyDiscoveryInterface.removeDeviceDiscoveryEvent(event);
    }
}
