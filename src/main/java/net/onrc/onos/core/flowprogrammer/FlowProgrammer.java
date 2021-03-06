package net.onrc.onos.core.flowprogrammer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IOFSwitch.PortChangeType;
import net.floodlightcontroller.core.IOFSwitchListener;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.restserver.IRestApiService;
import net.onrc.onos.core.flowprogrammer.web.FlowProgrammerWebRoutable;
import net.onrc.onos.core.registry.IControllerRegistryService;
import net.onrc.onos.core.util.Dpid;

import org.projectfloodlight.openflow.protocol.OFPortDesc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FlowProgrammer is a module responsible to maintain flows installed to
 * switches. FlowProgrammer consists of FlowPusher and FlowSynchronizer.
 * FlowPusher manages the rate of installation, and FlowSynchronizer
 * synchronizes flows between GraphDB and switches. FlowProgrammer also watch
 * the event of addition/deletion of switches to start/stop synchronization.
 * When a switch is added to network, FlowProgrammer immediately kicks
 * synchronization to keep switch's flow table latest state. Adversely, when a
 * switch is removed from network, FlowProgrammer immediately stops
 * synchronization.
 */
public class FlowProgrammer implements IFloodlightModule,
        IOFSwitchListener {
    // flag to enable FlowSynchronizer
    private static final boolean ENABLE_FLOW_SYNC = false;
    protected static final Logger log = LoggerFactory.getLogger(FlowProgrammer.class);
    protected volatile IFloodlightProviderService floodlightProvider;
    protected volatile IControllerRegistryService registryService;
    protected volatile IRestApiService restApi;

    protected FlowPusher pusher;
    private static final int NUM_PUSHER_THREAD = 1;

    protected FlowSynchronizer synchronizer;

    public FlowProgrammer() {
        pusher = new FlowPusher(NUM_PUSHER_THREAD);
        if (ENABLE_FLOW_SYNC) {
            synchronizer = new FlowSynchronizer();
        }
    }

    @Override
    public void init(FloodlightModuleContext context)
            throws FloodlightModuleException {
        floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
        registryService = context.getServiceImpl(IControllerRegistryService.class);
        restApi = context.getServiceImpl(IRestApiService.class);
        pusher.init(context);
        if (ENABLE_FLOW_SYNC) {
            synchronizer.init(pusher);
        }
    }

    @Override
    public void startUp(FloodlightModuleContext context) {
        restApi.addRestletRoutable(new FlowProgrammerWebRoutable());
        pusher.start();
        floodlightProvider.addOFSwitchListener(this);
    }

    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleServices() {
        Collection<Class<? extends IFloodlightService>> l =
                new ArrayList<Class<? extends IFloodlightService>>();
        l.add(IFlowPusherService.class);
        if (ENABLE_FLOW_SYNC) {
            l.add(IFlowSyncService.class);
        }
        return l;
    }

    @Override
    public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
        Map<Class<? extends IFloodlightService>, IFloodlightService> m =
                new HashMap<Class<? extends IFloodlightService>,
                        IFloodlightService>();
        m.put(IFlowPusherService.class, pusher);
        if (ENABLE_FLOW_SYNC) {
            m.put(IFlowSyncService.class, synchronizer);
        }
        return m;
    }

    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
        Collection<Class<? extends IFloodlightService>> l =
                new ArrayList<Class<? extends IFloodlightService>>();
        l.add(IFloodlightProviderService.class);
        l.add(IRestApiService.class);
        return l;
    }

    // ***********************
    // IOFSwitchListener
    // ***********************

    @Override
    public String getName() {
        return "FlowProgrammer";
    }

    @Override
    public void switchActivatedMaster(long swId) {
        IOFSwitch sw = floodlightProvider.getSwitch(swId);
        if (sw == null) {
            log.warn("Added switch not available {} ", swId);
            return;
        }
        log.debug("Switch added: {}", swId);

        if (ENABLE_FLOW_SYNC) {
            if (registryService.hasControl(swId)) {
                synchronizer.synchronize(sw);
            }
        }
    }

    @Override
    public void switchActivatedEqual(long swId) {
        // TODO Auto-generated method stub

    }

    @Override
    public void switchMasterToEqual(long swId) {
        // TODO Auto-generated method stub

    }

    @Override
    public void switchEqualToMaster(long swId) {
        // for now treat as switchActivatedMaster
        switchActivatedMaster(swId);
    }

    @Override
    public void switchDisconnected(long swId) {
        Dpid dpid = new Dpid(swId);
        log.debug("Switch removed: {}", dpid);

        //if (ENABLE_FLOW_SYNC) {
            //synchronizer.interrupt(sw);
        //}
        pusher.deleteQueue(dpid, true);
    }

    @Override
    public void switchPortChanged(long swId, OFPortDesc port, PortChangeType pct) {
        // TODO Auto-generated method stub
    }

}
