package net.floodlightcontroller.flowcache;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Collections;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.INetMapStorage;
import net.floodlightcontroller.core.INetMapTopologyObjects.IFlowEntry;
import net.floodlightcontroller.core.INetMapTopologyObjects.IFlowPath;
import net.floodlightcontroller.core.INetMapTopologyService.ITopoRouteService;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.flowcache.IFlowService;
import net.floodlightcontroller.flowcache.web.FlowWebRoutable;
import net.floodlightcontroller.restserver.IRestApiService;
import net.floodlightcontroller.util.CallerId;
import net.floodlightcontroller.util.DataPath;
import net.floodlightcontroller.util.Dpid;
import net.floodlightcontroller.util.DataPathEndpoints;
import net.floodlightcontroller.util.FlowEntry;
import net.floodlightcontroller.util.FlowEntryAction;
import net.floodlightcontroller.util.FlowEntryId;
import net.floodlightcontroller.util.FlowEntryMatch;
import net.floodlightcontroller.util.FlowEntrySwitchState;
import net.floodlightcontroller.util.FlowEntryUserState;
import net.floodlightcontroller.util.FlowId;
import net.floodlightcontroller.util.FlowPath;
import net.floodlightcontroller.util.IPv4Net;
import net.floodlightcontroller.util.MACAddress;
import net.floodlightcontroller.util.OFMessageDamper;
import net.floodlightcontroller.util.Port;
import net.floodlightcontroller.util.SwitchPort;
import net.onrc.onos.util.GraphDBConnection;
import net.onrc.onos.util.GraphDBConnection.Transaction;

import org.openflow.protocol.OFFlowMod;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFPacketOut;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFType;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FlowManager implements IFloodlightModule, IFlowService, INetMapStorage {

    public GraphDBConnection conn;

    protected IRestApiService restApi;
    protected IFloodlightProviderService floodlightProvider;
    protected FloodlightModuleContext context;

    protected OFMessageDamper messageDamper;

    //
    // TODO: Values copied from elsewhere (class LearningSwitch).
    // The local copy should go away!
    //
    protected static final int OFMESSAGE_DAMPER_CAPACITY = 50000; // TODO: find sweet spot
    protected static final int OFMESSAGE_DAMPER_TIMEOUT = 250;	// ms
    public static final short FLOWMOD_DEFAULT_IDLE_TIMEOUT = 0;	// infinity
    public static final short FLOWMOD_DEFAULT_HARD_TIMEOUT = 0;	// infinite
    public static final short PRIORITY_DEFAULT = 100;

    private static long nextFlowEntryId = 1;
    private static long measurementFlowId = 100000;
    private static String measurementFlowIdStr = "0x186a0";	// 100000
    private long modifiedMeasurementFlowTime = 0;

    /** The logger. */
    private static Logger log = LoggerFactory.getLogger(FlowManager.class);

    // The periodic task(s)
    private final ScheduledExecutorService measureShortestPathScheduler =
	Executors.newScheduledThreadPool(1);
    private final ScheduledExecutorService measureMapReaderScheduler =
	Executors.newScheduledThreadPool(1);
    private final ScheduledExecutorService mapReaderScheduler =
	Executors.newScheduledThreadPool(1);

    private BlockingQueue<Runnable> shortestPathQueue = new LinkedBlockingQueue<Runnable>();
    private ThreadPoolExecutor shortestPathExecutor =
	new ThreadPoolExecutor(10, 10, 5, TimeUnit.SECONDS, shortestPathQueue);

    class ShortestPathTask implements Runnable {
	private int hint;
	private ITopoRouteService topoRouteService;
	private ArrayList<DataPath> dpList;

	public ShortestPathTask(int hint,
				ITopoRouteService topoRouteService,
				ArrayList<DataPath> dpList) {
	    this.hint = hint;
	    this.topoRouteService = topoRouteService;
	    this.dpList = dpList;
	}

	@Override
	public void run() {
	    /*
	    String logMsg = "MEASUREMENT: Running Thread hint " + this.hint;
	    log.debug(logMsg);
	    long startTime = System.nanoTime();
	    */
	    for (DataPath dp : this.dpList) {
		topoRouteService.getTopoShortestPath(dp.srcPort(), dp.dstPort());
	    }
	    /*
	    long estimatedTime = System.nanoTime() - startTime;
	    double rate = (estimatedTime > 0)? ((double)dpList.size() * 1000000000) / estimatedTime: 0.0;
	    logMsg = "MEASUREMENT: Computed Thread hint " + hint + ": " + dpList.size() + " shortest paths in " + (double)estimatedTime / 1000000000 + " sec: " + rate + " flows/s";
	    log.debug(logMsg);
	    */
	}
    }

    final Runnable measureShortestPath = new Runnable() {
	    public void run() {
		log.debug("Recomputing Shortest Paths from the Network Map Flows...");
		if (floodlightProvider == null) {
		    log.debug("FloodlightProvider service not found!");
		    return;
		}

		ITopoRouteService topoRouteService =
		    context.getServiceImpl(ITopoRouteService.class);
		if (topoRouteService == null) {
		    log.debug("Topology Route Service not found");
		    return;
		}

		int leftoverQueueSize = shortestPathExecutor.getQueue().size();
		if (leftoverQueueSize > 0) {
		    String logMsg = "MEASUREMENT: Leftover Shortest Path Queue Size: " + leftoverQueueSize;
		    log.debug(logMsg);
		    return;
		}
		log.debug("MEASUREMENT: Beginning Shortest Path Computation");

		//
		// Recompute the Shortest Paths for all Flows
		//
		int counter = 0;
		int hint = 0;
		ArrayList<DataPath> dpList = new ArrayList<DataPath>();
		long startTime = System.nanoTime();

		topoRouteService.prepareShortestPathTopo();

		Iterable<IFlowPath> allFlowPaths = conn.utils().getAllFlowPaths(conn);
		for (IFlowPath flowPathObj : allFlowPaths) {
		    FlowId flowId = new FlowId(flowPathObj.getFlowId());

		    // log.debug("Found Path {}", flowId.toString());
		    Dpid srcDpid = new Dpid(flowPathObj.getSrcSwitch());
		    Port srcPort = new Port(flowPathObj.getSrcPort());
		    Dpid dstDpid = new Dpid(flowPathObj.getDstSwitch());
		    Port dstPort = new Port(flowPathObj.getDstPort());
		    SwitchPort srcSwitchPort = new SwitchPort(srcDpid, srcPort);
		    SwitchPort dstSwitchPort = new SwitchPort(dstDpid, dstPort);

		    /*
		    DataPath dp = new DataPath();
		    dp.setSrcPort(srcSwitchPort);
		    dp.setDstPort(dstSwitchPort);
		    dpList.add(dp);
		    if ((dpList.size() % 10) == 0) {
			shortestPathExecutor.execute(
				new ShortestPathTask(hint, topoRouteService,
						     dpList));
			dpList = new ArrayList<DataPath>();
			hint++;
		    }
		    */

		    DataPath dataPath =
			topoRouteService.getTopoShortestPath(srcSwitchPort,
							     dstSwitchPort);
		    counter++;
		}
		if (dpList.size() > 0) {
		    shortestPathExecutor.execute(
			new ShortestPathTask(hint, topoRouteService,
					     dpList));
		}

		/*
		// Wait for all tasks to finish
		try {
		    while (shortestPathExecutor.getQueue().size() > 0) {
			Thread.sleep(100);
		    }
		} catch (InterruptedException ex) {
		    log.debug("MEASUREMENT: Shortest Path Computation interrupted");
		}
		*/

		conn.endTx(Transaction.COMMIT);
		topoRouteService.dropShortestPathTopo();

		long estimatedTime = System.nanoTime() - startTime;
		double rate = (estimatedTime > 0)? ((double)counter * 1000000000) / estimatedTime: 0.0;
		String logMsg = "MEASUREMENT: Computed " + counter + " shortest paths in " + (double)estimatedTime / 1000000000 + " sec: " + rate + " flows/s";
		log.debug(logMsg);
	    }
	};

    final Runnable measureMapReader = new Runnable() {
	    public void run() {
		if (floodlightProvider == null) {
		    log.debug("FloodlightProvider service not found!");
		    return;
		}

		//
		// Fetch all Flow Entries
		//
		int counter = 0;
		long startTime = System.nanoTime();
		Iterable<IFlowEntry> allFlowEntries = conn.utils().getAllFlowEntries(conn);
		for (IFlowEntry flowEntryObj : allFlowEntries) {
		    counter++;
		    FlowEntryId flowEntryId =
			new FlowEntryId(flowEntryObj.getFlowEntryId());
		    String userState = flowEntryObj.getUserState();
		    String switchState = flowEntryObj.getSwitchState();
		}
		conn.endTx(Transaction.COMMIT);

		long estimatedTime = System.nanoTime() - startTime;
		double rate = (estimatedTime > 0)? ((double)counter * 1000000000) / estimatedTime: 0.0;
		String logMsg = "MEASUREMENT: Fetched " + counter + " flow entries in " + (double)estimatedTime / 1000000000 + " sec: " + rate + " entries/s";
		log.debug(logMsg);
	    }
	};

    final Runnable mapReader = new Runnable() {
	    public void run() {
		if (floodlightProvider == null) {
		    log.debug("FloodlightProvider service not found!");
		    return;
		}

		Map<Long, IOFSwitch> mySwitches =
		    floodlightProvider.getSwitches();
		Map<Long, IFlowEntry> myFlowEntries =
		    new TreeMap<Long, IFlowEntry>();
		LinkedList<IFlowEntry> deleteFlowEntries =
		    new LinkedList<IFlowEntry>();

		//
		// Fetch all Flow Entries and select only my Flow Entries
		// that need to be undated into the switches.
		//
		Iterable<IFlowEntry> allFlowEntries =
		    conn.utils().getAllFlowEntries(conn);
		for (IFlowEntry flowEntryObj : allFlowEntries) {
		    String flowEntryIdStr = flowEntryObj.getFlowEntryId();
		    String userState = flowEntryObj.getUserState();
		    String switchState = flowEntryObj.getSwitchState();
		    String dpidStr = flowEntryObj.getSwitchDpid();
		    if ((flowEntryIdStr == null) ||
			(userState == null) ||
			(switchState == null) ||
			(dpidStr == null)) {
			log.debug("IGNORING Flow Entry entry with null fields");
			continue;
		    }
		    FlowEntryId flowEntryId = new FlowEntryId(flowEntryIdStr);
		    Dpid dpid = new Dpid(dpidStr);

		    /*
		    log.debug("Found Flow Entry Id = {} {}",
			      flowEntryId.toString(),
			      "DPID = " + dpid.toString() +
			      " User State: " + userState +
			      " Switch State: " + switchState);
		    */

		    if (! switchState.equals("FE_SWITCH_NOT_UPDATED"))
			continue;	// Ignore the entry: nothing to do

		    IOFSwitch mySwitch = mySwitches.get(dpid.value());
		    if (mySwitch == null)
			continue;	// Ignore the entry: not my switch

		    myFlowEntries.put(flowEntryId.value(), flowEntryObj);
		}

		//
		// Process my Flow Entries
		//
		Boolean processed_measurement_flow = false;
		for (Map.Entry<Long, IFlowEntry> entry : myFlowEntries.entrySet()) {
		    IFlowEntry flowEntryObj = entry.getValue();
		    // Code for measurement purpose
		    {
			IFlowPath flowObj =
			    conn.utils().getFlowPathByFlowEntry(conn,
								flowEntryObj);
			if ((flowObj != null) &&
			    flowObj.getFlowId().equals(measurementFlowIdStr)) {
			    processed_measurement_flow = true;
			}
		    }

		    //
		    // TODO: Eliminate the re-fetching of flowEntryId,
		    // userState, switchState, and dpid from the flowEntryObj.
		    //
		    FlowEntryId flowEntryId =
			new FlowEntryId(flowEntryObj.getFlowEntryId());
		    Dpid dpid = new Dpid(flowEntryObj.getSwitchDpid());
		    String userState = flowEntryObj.getUserState();
		    String switchState = flowEntryObj.getSwitchState();
		    IOFSwitch mySwitch = mySwitches.get(dpid.value());
		    if (mySwitch == null)
			continue;		// Shouldn't happen

		    //
		    // Create the Open Flow Flow Modification Entry to push
		    //
		    OFFlowMod fm =
			(OFFlowMod) floodlightProvider.getOFMessageFactory()
			.getMessage(OFType.FLOW_MOD);
		    long cookie = flowEntryId.value();

		    short flowModCommand = OFFlowMod.OFPFC_ADD;
		    if (userState.equals("FE_USER_ADD")) {
			flowModCommand = OFFlowMod.OFPFC_ADD;
		    } else if (userState.equals("FE_USER_MODIFY")) {
			flowModCommand = OFFlowMod.OFPFC_MODIFY_STRICT;
		    } else if (userState.equals("FE_USER_DELETE")) {
			flowModCommand = OFFlowMod.OFPFC_DELETE_STRICT;
		    } else {
			// Unknown user state. Ignore the entry
			log.debug("Flow Entry ignored (FlowEntryId = {}): unknown user state {}",
				  flowEntryId.toString(), userState);
			continue;
		    }

		    //
		    // Fetch the match conditions
		    //
		    OFMatch match = new OFMatch();
		    match.setWildcards(OFMatch.OFPFW_ALL);
		    Short matchInPort = flowEntryObj.getMatchInPort();
		    if (matchInPort != null) {
			match.setInputPort(matchInPort);
			match.setWildcards(match.getWildcards() & ~OFMatch.OFPFW_IN_PORT);
		    }
		    Short matchEthernetFrameType = flowEntryObj.getMatchEthernetFrameType();
		    if (matchEthernetFrameType != null) {
			match.setDataLayerType(matchEthernetFrameType);
			match.setWildcards(match.getWildcards() & ~OFMatch.OFPFW_DL_TYPE);
		    }
		    String matchSrcIPv4Net = flowEntryObj.getMatchSrcIPv4Net();
		    if (matchSrcIPv4Net != null) {
			match.setFromCIDR(matchSrcIPv4Net, OFMatch.STR_NW_SRC);
		    }
		    String matchDstIPv4Net = flowEntryObj.getMatchDstIPv4Net();
		    if (matchDstIPv4Net != null) {
			match.setFromCIDR(matchDstIPv4Net, OFMatch.STR_NW_DST);
		    }
		    String matchSrcMac = flowEntryObj.getMatchSrcMac();
		    if (matchSrcMac != null) {
			match.setDataLayerSource(matchSrcMac);
			match.setWildcards(match.getWildcards() & ~OFMatch.OFPFW_DL_SRC);
		    }
		    String matchDstMac = flowEntryObj.getMatchDstMac();
		    if (matchDstMac != null) {
			match.setDataLayerDestination(matchDstMac);
			match.setWildcards(match.getWildcards() & ~OFMatch.OFPFW_DL_DST);
		    }

		    //
		    // Fetch the actions
		    //
		    List<OFAction> actions = new ArrayList<OFAction>();
		    Short actionOutputPort = flowEntryObj.getActionOutput();
		    if (actionOutputPort != null) {
			OFActionOutput action = new OFActionOutput();
			// XXX: The max length is hard-coded for now
			action.setMaxLength((short)0xffff);
			action.setPort(actionOutputPort);
			actions.add(action);
		    }

		    fm.setIdleTimeout(FLOWMOD_DEFAULT_IDLE_TIMEOUT)
			.setHardTimeout(FLOWMOD_DEFAULT_HARD_TIMEOUT)
			.setPriority(PRIORITY_DEFAULT)
			.setBufferId(OFPacketOut.BUFFER_ID_NONE)
			.setCookie(cookie)
			.setCommand(flowModCommand)
			.setMatch(match)
			.setActions(actions)
			.setLengthU(OFFlowMod.MINIMUM_LENGTH+OFActionOutput.MINIMUM_LENGTH);
		    fm.setOutPort(OFPort.OFPP_NONE.getValue());
		    if ((flowModCommand == OFFlowMod.OFPFC_DELETE) ||
			(flowModCommand == OFFlowMod.OFPFC_DELETE_STRICT)) {
			if (actionOutputPort != null)
			    fm.setOutPort(actionOutputPort);
		    }

		    //
		    // TODO: Set the following flag
		    // fm.setFlags(OFFlowMod.OFPFF_SEND_FLOW_REM);
		    // See method ForwardingBase::pushRoute()
		    //
		    try {
			messageDamper.write(mySwitch, fm, null);
			mySwitch.flush();
			//
			// TODO: We should use the OpenFlow Barrier mechanism
			// to check for errors, and update the SwitchState
			// for a flow entry after the Barrier message is
			// is received.
			//
			flowEntryObj.setSwitchState("FE_SWITCH_UPDATED");
			if (userState.equals("FE_USER_DELETE")) {
			    // An entry that needs to be deleted.
			    deleteFlowEntries.add(flowEntryObj);
			}
		    } catch (IOException e) {
			log.error("Failure writing flow mod from network map", e);
		    }
		}

		//
		// Delete all entries marked for deletion
		//
		// TODO: We should use the OpenFlow Barrier mechanism
		// to check for errors, and delete the Flow Entries after the
		// Barrier message is received.
		//
		while (! deleteFlowEntries.isEmpty()) {
		    IFlowEntry flowEntryObj = deleteFlowEntries.poll();
		    IFlowPath flowObj =
			conn.utils().getFlowPathByFlowEntry(conn, flowEntryObj);
		    if (flowObj == null) {
			log.debug("Did not find FlowPath to be deleted");
			continue;
		    }
		    flowObj.removeFlowEntry(flowEntryObj);
		    conn.utils().removeFlowEntry(conn, flowEntryObj);

		    // Test whether the last flow entry
		    Iterable<IFlowEntry> tmpflowEntries =
			flowObj.getFlowEntries();
		    boolean found = false;
		    for (IFlowEntry tmpflowEntryObj : tmpflowEntries) {
			found = true;
			break;
		    }
		    if (! found) {
			// Remove the Flow Path as well
			conn.utils().removeFlowPath(conn, flowObj);
		    }
		}
		conn.endTx(Transaction.COMMIT);

		if (processed_measurement_flow) {
		    long estimatedTime = System.nanoTime() - modifiedMeasurementFlowTime;
		    String logMsg = "MEASUREMENT: Pushed Flow delay: " +
			(double)estimatedTime / 1000000000 + " sec";
		    log.debug(logMsg);
		}
	    }
	};

    /*
    final ScheduledFuture<?> measureShortestPathHandle =
	measureShortestPathScheduler.scheduleAtFixedRate(measureShortestPath, 10, 10, TimeUnit.SECONDS);
    */

    /*
    final ScheduledFuture<?> measureMapReaderHandle =
	measureMapReaderScheduler.scheduleAtFixedRate(measureMapReader, 10, 10, TimeUnit.SECONDS);
    */

    final ScheduledFuture<?> mapReaderHandle =
	mapReaderScheduler.scheduleAtFixedRate(mapReader, 3, 3, TimeUnit.SECONDS);

    @Override
    public void init(String conf) {
	conn = GraphDBConnection.getInstance(conf);
    }

    public void finalize() {
	close();
    }

    @Override
    public void close() {
	conn.close();
    }

    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleServices() {
        Collection<Class<? extends IFloodlightService>> l = 
            new ArrayList<Class<? extends IFloodlightService>>();
        l.add(IFlowService.class);
        return l;
    }

    @Override
    public Map<Class<? extends IFloodlightService>, IFloodlightService> 
			       getServiceImpls() {
        Map<Class<? extends IFloodlightService>,
        IFloodlightService> m = 
            new HashMap<Class<? extends IFloodlightService>,
                IFloodlightService>();
        m.put(IFlowService.class, this);
        return m;
    }

    @Override
    public Collection<Class<? extends IFloodlightService>> 
                                                    getModuleDependencies() {
	Collection<Class<? extends IFloodlightService>> l =
	    new ArrayList<Class<? extends IFloodlightService>>();
	l.add(IFloodlightProviderService.class);
	l.add(IRestApiService.class);
        return l;
    }

    @Override
    public void init(FloodlightModuleContext context)
	throws FloodlightModuleException {
	this.context = context;
	floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
	restApi = context.getServiceImpl(IRestApiService.class);
	messageDamper = new OFMessageDamper(OFMESSAGE_DAMPER_CAPACITY,
					    EnumSet.of(OFType.FLOW_MOD),
					    OFMESSAGE_DAMPER_TIMEOUT);
	// TODO: An ugly hack!
	String conf = "/tmp/cassandra.titan";
	this.init(conf);
    }

    @Override
    public void startUp(FloodlightModuleContext context) {
	restApi.addRestletRoutable(new FlowWebRoutable());

	//
	// Extract all flow entries and assign the next Flow Entry ID
	// to be larger than the largest Flow Entry ID
	//
	Iterable<IFlowEntry> allFlowEntries = conn.utils().getAllFlowEntries(conn);
	for (IFlowEntry flowEntryObj : allFlowEntries) {
	    FlowEntryId flowEntryId =
		new FlowEntryId(flowEntryObj.getFlowEntryId());
	    if (flowEntryId.value() >= nextFlowEntryId)
		nextFlowEntryId = flowEntryId.value() + 1;
	}
	conn.endTx(Transaction.COMMIT);
    }

    /**
     * Add a flow.
     *
     * Internally, ONOS will automatically register the installer for
     * receiving Flow Path Notifications for that path.
     *
     * @param flowPath the Flow Path to install.
     * @param flowId the return-by-reference Flow ID as assigned internally.
     * @return true on success, otherwise false.
     */
    @Override
    public boolean addFlow(FlowPath flowPath, FlowId flowId) {
	if (flowPath.flowId().value() == measurementFlowId) {
	    modifiedMeasurementFlowTime = System.nanoTime();
	}

	//
	// Assign the FlowEntry IDs
	// Right now every new flow entry gets a new flow entry ID
	// TODO: This needs to be redesigned!
	//
	for (FlowEntry flowEntry : flowPath.dataPath().flowEntries()) {
	    long id = nextFlowEntryId++;
	    flowEntry.setFlowEntryId(new FlowEntryId(id));
	}

	IFlowPath flowObj = null;
	try {
	    if ((flowObj = conn.utils().searchFlowPath(conn, flowPath.flowId()))
		!= null) {
		log.debug("Adding FlowPath with FlowId {}: found existing FlowPath",
			  flowPath.flowId().toString());
	    } else {
		flowObj = conn.utils().newFlowPath(conn);
		log.debug("Adding FlowPath with FlowId {}: creating new FlowPath",
			  flowPath.flowId().toString());
	    }
	} catch (Exception e) {
	    // TODO: handle exceptions
	    conn.endTx(Transaction.ROLLBACK);
	    log.error(":addFlow FlowId:{} failed",
		      flowPath.flowId().toString());
	}
	if (flowObj == null) {
	    log.error(":addFlow FlowId:{} failed: Flow object not created",
		      flowPath.flowId().toString());
	    conn.endTx(Transaction.ROLLBACK);
	    return false;
	}

	//
	// Set the Flow key:
	// - flowId
	//
	flowObj.setFlowId(flowPath.flowId().toString());
	flowObj.setType("flow");

	//
	// Set the Flow attributes:
	// - flowPath.installerId()
	// - flowPath.dataPath().srcPort()
	// - flowPath.dataPath().dstPort()
	//
	flowObj.setInstallerId(flowPath.installerId().toString());
	flowObj.setSrcSwitch(flowPath.dataPath().srcPort().dpid().toString());
	flowObj.setSrcPort(flowPath.dataPath().srcPort().port().value());
	flowObj.setDstSwitch(flowPath.dataPath().dstPort().dpid().toString());
	flowObj.setDstPort(flowPath.dataPath().dstPort().port().value());

	// Flow edges:
	//   HeadFE


	//
	// Flow Entries:
	// flowPath.dataPath().flowEntries()
	//
	for (FlowEntry flowEntry : flowPath.dataPath().flowEntries()) {
	    IFlowEntry flowEntryObj = null;
	    boolean found = false;
	    try {
		if ((flowEntryObj = conn.utils().searchFlowEntry(conn, flowEntry.flowEntryId())) != null) {
		    log.debug("Adding FlowEntry with FlowEntryId {}: found existing FlowEntry",
			      flowEntry.flowEntryId().toString());
		    found = true;
		} else {
		    flowEntryObj = conn.utils().newFlowEntry(conn);
		    log.debug("Adding FlowEntry with FlowEntryId {}: creating new FlowEntry",
			      flowEntry.flowEntryId().toString());
		}
	    } catch (Exception e) {
		// TODO: handle exceptions
		conn.endTx(Transaction.ROLLBACK);
		log.error(":addFlow FlowEntryId:{} failed",
			  flowEntry.flowEntryId().toString());
	    }
	    if (flowEntryObj == null) {
		log.error(":addFlow FlowEntryId:{} failed: FlowEntry object not created",
		      flowEntry.flowEntryId().toString());
		conn.endTx(Transaction.ROLLBACK);
		return false;
	    }

	    //
	    // Set the Flow Entry key:
	    // - flowEntry.flowEntryId()
	    //
	    flowEntryObj.setFlowEntryId(flowEntry.flowEntryId().toString());
	    flowEntryObj.setType("flow_entry");

	    // 
	    // Set the Flow Entry attributes:
	    // - flowEntry.flowEntryMatch()
	    // - flowEntry.flowEntryActions()
	    // - flowEntry.dpid()
	    // - flowEntry.flowEntryUserState()
	    // - flowEntry.flowEntrySwitchState()
	    // - flowEntry.flowEntryErrorState()
	    // - flowEntry.matchInPort()
	    // - flowEntry.matchEthernetFrameType()
	    // - flowEntry.matchSrcIPv4Net()
	    // - flowEntry.matchDstIPv4Net()
	    // - flowEntry.matchSrcMac()
	    // - flowEntry.matchDstMac()
	    // - flowEntry.actionOutput()
	    //
	    flowEntryObj.setSwitchDpid(flowEntry.dpid().toString());
	    if (flowEntry.flowEntryMatch().matchInPort())
		flowEntryObj.setMatchInPort(flowEntry.flowEntryMatch().inPort().value());
	    if (flowEntry.flowEntryMatch().matchEthernetFrameType())
		flowEntryObj.setMatchEthernetFrameType(flowEntry.flowEntryMatch().ethernetFrameType());
	    if (flowEntry.flowEntryMatch().matchSrcIPv4Net())
		flowEntryObj.setMatchSrcIPv4Net(flowEntry.flowEntryMatch().srcIPv4Net().toString());
	    if (flowEntry.flowEntryMatch().matchDstIPv4Net())
		flowEntryObj.setMatchDstIPv4Net(flowEntry.flowEntryMatch().dstIPv4Net().toString());
	    if (flowEntry.flowEntryMatch().matchSrcMac())
		flowEntryObj.setMatchSrcMac(flowEntry.flowEntryMatch().srcMac().toString());
	    if (flowEntry.flowEntryMatch().matchDstMac())
		flowEntryObj.setMatchDstMac(flowEntry.flowEntryMatch().dstMac().toString());

	    for (FlowEntryAction fa : flowEntry.flowEntryActions()) {
		if (fa.actionOutput() != null)
		    flowEntryObj.setActionOutput(fa.actionOutput().port().value());
	    }
	    // TODO: Hacks with hard-coded state names!
	    if (found)
		flowEntryObj.setUserState("FE_USER_MODIFY");
	    else
		flowEntryObj.setUserState("FE_USER_ADD");
	    flowEntryObj.setSwitchState("FE_SWITCH_NOT_UPDATED");
	    //
	    // TODO: Take care of the FlowEntryMatch, FlowEntryAction set,
	    // and FlowEntryErrorState.
	    //

	    // Flow Entries edges:
	    //   Flow
	    //   NextFE
	    //   InPort
	    //   OutPort
	    //   Switch
	    if (! found)
		flowObj.addFlowEntry(flowEntryObj);
	}
	conn.endTx(Transaction.COMMIT);

	//
	// TODO: We need a proper Flow ID allocation mechanism.
	//
	flowId.setValue(flowPath.flowId().value());

	return true;
    }

    /**
     * Delete a previously added flow.
     *
     * @param flowId the Flow ID of the flow to delete.
     * @return true on success, otherwise false.
     */
    @Override
    public boolean deleteFlow(FlowId flowId) {
	if (flowId.value() == measurementFlowId) {
	    modifiedMeasurementFlowTime = System.nanoTime();
	}

	IFlowPath flowObj = null;
	//
	// We just mark the entries for deletion,
	// and let the switches remove each individual entry after
	// it has been removed from the switches.
	//
	try {
	    if ((flowObj = conn.utils().searchFlowPath(conn, flowId))
		!= null) {
		log.debug("Deleting FlowPath with FlowId {}: found existing FlowPath",
			  flowId.toString());
	    } else {
		log.debug("Deleting FlowPath with FlowId {}:  FlowPath not found",
			  flowId.toString());
	    }
	} catch (Exception e) {
	    // TODO: handle exceptions
	    conn.endTx(Transaction.ROLLBACK);
	    log.error(":deleteFlow FlowId:{} failed", flowId.toString());
	}
	if (flowObj == null) {
	    conn.endTx(Transaction.COMMIT);
	    return true;		// OK: No such flow
	}

	//
	// Find and mark for deletion all Flow Entries
	//
	Iterable<IFlowEntry> flowEntries = flowObj.getFlowEntries();
	boolean empty = true;	// TODO: an ugly hack
	for (IFlowEntry flowEntryObj : flowEntries) {
	    empty = false;
	    // flowObj.removeFlowEntry(flowEntryObj);
	    // conn.utils().removeFlowEntry(conn, flowEntryObj);
	    flowEntryObj.setUserState("FE_USER_DELETE");
	    flowEntryObj.setSwitchState("FE_SWITCH_NOT_UPDATED");
	}
	// Remove from the database empty flows
	if (empty)
	    conn.utils().removeFlowPath(conn, flowObj);
	conn.endTx(Transaction.COMMIT);

	return true;
    }

    /**
     * Clear the state for a previously added flow.
     *
     * @param flowId the Flow ID of the flow to clear.
     * @return true on success, otherwise false.
     */
    @Override
    public boolean clearFlow(FlowId flowId) {
	IFlowPath flowObj = null;
	try {
	    if ((flowObj = conn.utils().searchFlowPath(conn, flowId))
		!= null) {
		log.debug("Clearing FlowPath with FlowId {}: found existing FlowPath",
			  flowId.toString());
	    } else {
		log.debug("Clearing FlowPath with FlowId {}:  FlowPath not found",
			  flowId.toString());
	    }
	} catch (Exception e) {
	    // TODO: handle exceptions
	    conn.endTx(Transaction.ROLLBACK);
	    log.error(":clearFlow FlowId:{} failed", flowId.toString());
	}
	if (flowObj == null) {
	    conn.endTx(Transaction.COMMIT);
	    return true;		// OK: No such flow
	}

	//
	// Remove all Flow Entries
	//
	Iterable<IFlowEntry> flowEntries = flowObj.getFlowEntries();
	for (IFlowEntry flowEntryObj : flowEntries) {
	    flowObj.removeFlowEntry(flowEntryObj);
	    conn.utils().removeFlowEntry(conn, flowEntryObj);
	}
	// Remove the Flow itself
	conn.utils().removeFlowPath(conn, flowObj);
	conn.endTx(Transaction.COMMIT);

	return true;
    }

    /**
     * Get a previously added flow.
     *
     * @param flowId the Flow ID of the flow to get.
     * @return the Flow Path if found, otherwise null.
     */
    @Override
    public FlowPath getFlow(FlowId flowId) {
	IFlowPath flowObj = null;
	try {
	    if ((flowObj = conn.utils().searchFlowPath(conn, flowId))
		!= null) {
		log.debug("Get FlowPath with FlowId {}: found existing FlowPath",
			  flowId.toString());
	    } else {
		log.debug("Get FlowPath with FlowId {}:  FlowPath not found",
			  flowId.toString());
	    }
	} catch (Exception e) {
	    // TODO: handle exceptions
	    conn.endTx(Transaction.ROLLBACK);
	    log.error(":getFlow FlowId:{} failed", flowId.toString());
	}
	if (flowObj == null) {
	    conn.endTx(Transaction.COMMIT);
	    return null;		// Flow not found
	}

	//
	// Extract the Flow state
	//
	FlowPath flowPath = extractFlowPath(flowObj);
	conn.endTx(Transaction.COMMIT);

	return flowPath;
    }

    /**
     * Get all previously added flows by a specific installer for a given
     * data path endpoints.
     *
     * @param installerId the Caller ID of the installer of the flow to get.
     * @param dataPathEndpoints the data path endpoints of the flow to get.
     * @return the Flow Paths if found, otherwise null.
     */
    @Override
    public ArrayList<FlowPath> getAllFlows(CallerId installerId,
					   DataPathEndpoints dataPathEndpoints) {
	//
	// TODO: The implementation below is not optimal:
	// We fetch all flows, and then return only the subset that match
	// the query conditions.
	// We should use the appropriate Titan/Gremlin query to filter-out
	// the flows as appropriate.
	//
	ArrayList<FlowPath> allFlows = getAllFlows();

	if (allFlows == null) {
	    log.debug("Get FlowPaths for installerId{} and dataPathEndpoints{}: no FlowPaths found", installerId, dataPathEndpoints);
	    return null;
	}

	ArrayList<FlowPath> flowPaths = new ArrayList<FlowPath>();
	for (FlowPath flow : allFlows) {
	    //
	    // TODO: String-based comparison is sub-optimal.
	    // We are using it for now to save us the extra work of
	    // implementing the "equals()" and "hashCode()" methods.
	    //
	    if (! flow.installerId().toString().equals(installerId.toString()))
		continue;
	    if (! flow.dataPath().srcPort().toString().equals(dataPathEndpoints.srcPort().toString())) {
		continue;
	    }
	    if (! flow.dataPath().dstPort().toString().equals(dataPathEndpoints.dstPort().toString())) {
		continue;
	    }
	    flowPaths.add(flow);
	}

	if (flowPaths.isEmpty()) {
	    log.debug("Get FlowPaths for installerId{} and dataPathEndpoints{}: no FlowPaths found", installerId, dataPathEndpoints);
	    flowPaths = null;
	} else {
	    log.debug("Get FlowPaths for installerId{} and dataPathEndpoints{}: FlowPaths are found", installerId, dataPathEndpoints);
	}

	return flowPaths;
    }

    /**
     * Get all installed flows by all installers for given data path endpoints.
     *
     * @param dataPathEndpoints the data path endpoints of the flows to get.
     * @return the Flow Paths if found, otherwise null.
     */
    @Override
    public ArrayList<FlowPath> getAllFlows(DataPathEndpoints dataPathEndpoints) {
	//
	// TODO: The implementation below is not optimal:
	// We fetch all flows, and then return only the subset that match
	// the query conditions.
	// We should use the appropriate Titan/Gremlin query to filter-out
	// the flows as appropriate.
	//
	ArrayList<FlowPath> allFlows = getAllFlows();

	if (allFlows == null) {
	    log.debug("Get FlowPaths for dataPathEndpoints{}: no FlowPaths found", dataPathEndpoints);
	    return null;
	}

	ArrayList<FlowPath> flowPaths = new ArrayList<FlowPath>();
	for (FlowPath flow : allFlows) {
	    //
	    // TODO: String-based comparison is sub-optimal.
	    // We are using it for now to save us the extra work of
	    // implementing the "equals()" and "hashCode()" methods.
	    //
	    if (! flow.dataPath().srcPort().toString().equals(dataPathEndpoints.srcPort().toString())) {
		continue;
	    }
	    if (! flow.dataPath().dstPort().toString().equals(dataPathEndpoints.dstPort().toString())) {
		continue;
	    }
	    flowPaths.add(flow);
	}

	if (flowPaths.isEmpty()) {
	    log.debug("Get FlowPaths for dataPathEndpoints{}: no FlowPaths found", dataPathEndpoints);
	    flowPaths = null;
	} else {
	    log.debug("Get FlowPaths for dataPathEndpoints{}: FlowPaths are found", dataPathEndpoints);
	}

	return flowPaths;
    }

    /**
     * Get summary of all installed flows by all installers in a given range
     *
     * @param flowId the data path endpoints of the flows to get.
     * @param maxFlows: the maximum number of flows to be returned
     * @return the Flow Paths if found, otherwise null.
     */
    @Override
    public ArrayList<FlowPath> getAllFlowsSummary(FlowId flowId, int maxFlows) {
		//
		// TODO: The implementation below is not optimal:
		// We fetch all flows, and then return only the subset that match
		// the query conditions.
		// We should use the appropriate Titan/Gremlin query to filter-out
		// the flows as appropriate.
		//
	    ArrayList<FlowPath> allFlows = getAllFlows();
	
		if (allFlows == null) {
		    log.debug("Get FlowPathsSummary for {} {}: no FlowPaths found", flowId, maxFlows);
		    return null;
		}
	
		Collections.sort(allFlows);
		
		ArrayList<FlowPath> flowPaths = new ArrayList<FlowPath>();
		for (FlowPath flow : allFlows) {
			
			// start from desired flowId
			if (flow.flowId().value() < flowId.value()) {
				continue;
			}
			
			// Summarize by making null flow entry fields that are not relevant to report
			for (FlowEntry flowEntry : flow.dataPath().flowEntries()) {
				flowEntry.setFlowEntryActions(null);
				flowEntry.setFlowEntryMatch(null);
			}
			
		    flowPaths.add(flow);
		    if (maxFlows != 0 && flowPaths.size() >= maxFlows) {
		    	break;
		    }
		}
	
		if (flowPaths.isEmpty()) {
		    log.debug("Get FlowPathsSummary {} {}: no FlowPaths found", flowId, maxFlows);
		    flowPaths = null;
		} else {
		    log.debug("Get FlowPathsSummary for {} {}: FlowPaths were found", flowId, maxFlows);
		}
	
		return flowPaths;
    }
    
    /**
     * Get all installed flows by all installers.
     *
     * @return the Flow Paths if found, otherwise null.
     */
    @Override
    public ArrayList<FlowPath> getAllFlows() {
	Iterable<IFlowPath> flowPathsObj = null;

	try {
	    if ((flowPathsObj = conn.utils().getAllFlowPaths(conn)) != null) {
		log.debug("Get all FlowPaths: found FlowPaths");
	    } else {
		log.debug("Get all FlowPaths: no FlowPaths found");
	    }
	} catch (Exception e) {
	    // TODO: handle exceptions
	    conn.endTx(Transaction.ROLLBACK);
	    log.error(":getAllFlowPaths failed");
	}
	if ((flowPathsObj == null) || (flowPathsObj.iterator().hasNext() == false)) {
	    conn.endTx(Transaction.COMMIT);
	    return null;	// No Flows found
	}

	ArrayList<FlowPath> flowPaths = new ArrayList<FlowPath>();
	for (IFlowPath flowObj : flowPathsObj) {
	    //
	    // Extract the Flow state
	    //
	    FlowPath flowPath = extractFlowPath(flowObj);
	    flowPaths.add(flowPath);
	}

	conn.endTx(Transaction.COMMIT);

	return flowPaths;
    }

    /**
     * Extract Flow Path State from a Titan Database Object @ref IFlowPath.
     *
     * @param flowObj the object to extract the Flow Path State from.
     * @return the extracted Flow Path State.
     */
    private FlowPath extractFlowPath(IFlowPath flowObj) {
	FlowPath flowPath = new FlowPath();

	//
	// Extract the Flow state
	//
	flowPath.setFlowId(new FlowId(flowObj.getFlowId()));
	flowPath.setInstallerId(new CallerId(flowObj.getInstallerId()));
	flowPath.dataPath().srcPort().setDpid(new Dpid(flowObj.getSrcSwitch()));
	flowPath.dataPath().srcPort().setPort(new Port(flowObj.getSrcPort()));
	flowPath.dataPath().dstPort().setDpid(new Dpid(flowObj.getDstSwitch()));
	flowPath.dataPath().dstPort().setPort(new Port(flowObj.getDstPort()));

	//
	// Extract all Flow Entries
	//
	Iterable<IFlowEntry> flowEntries = flowObj.getFlowEntries();
	for (IFlowEntry flowEntryObj : flowEntries) {
	    FlowEntry flowEntry = new FlowEntry();
	    flowEntry.setFlowEntryId(new FlowEntryId(flowEntryObj.getFlowEntryId()));
	    flowEntry.setDpid(new Dpid(flowEntryObj.getSwitchDpid()));

	    //
	    // Extract the match conditions
	    //
	    FlowEntryMatch match = new FlowEntryMatch();
	    Short matchInPort = flowEntryObj.getMatchInPort();
	    if (matchInPort != null)
		match.enableInPort(new Port(matchInPort));
	    Short matchEthernetFrameType = flowEntryObj.getMatchEthernetFrameType();
	    if (matchEthernetFrameType != null)
		match.enableEthernetFrameType(matchEthernetFrameType);
	    String matchSrcIPv4Net = flowEntryObj.getMatchSrcIPv4Net();
	    if (matchSrcIPv4Net != null)
		match.enableSrcIPv4Net(new IPv4Net(matchSrcIPv4Net));
	    String matchDstIPv4Net = flowEntryObj.getMatchDstIPv4Net();
	    if (matchDstIPv4Net != null)
		match.enableDstIPv4Net(new IPv4Net(matchDstIPv4Net));
	    String matchSrcMac = flowEntryObj.getMatchSrcMac();
	    if (matchSrcMac != null)
		match.enableSrcMac(MACAddress.valueOf(matchSrcMac));
	    String matchDstMac = flowEntryObj.getMatchDstMac();
	    if (matchDstMac != null)
		match.enableDstMac(MACAddress.valueOf(matchDstMac));
	    flowEntry.setFlowEntryMatch(match);

	    //
	    // Extract the actions
	    //
	    ArrayList<FlowEntryAction> actions = new ArrayList<FlowEntryAction>();
	    Short actionOutputPort = flowEntryObj.getActionOutput();
	    if (actionOutputPort != null) {
		FlowEntryAction action = new FlowEntryAction();
		action.setActionOutput(new Port(actionOutputPort));
		actions.add(action);
	    }
	    flowEntry.setFlowEntryActions(actions);

	    String userState = flowEntryObj.getUserState();
	    flowEntry.setFlowEntryUserState(FlowEntryUserState.valueOf(userState));
	    String switchState = flowEntryObj.getSwitchState();
	    flowEntry.setFlowEntrySwitchState(FlowEntrySwitchState.valueOf(switchState));
	    //
	    // TODO: Take care of the FlowEntryMatch, FlowEntryAction set,
	    // and FlowEntryErrorState.
	    //
	    flowPath.dataPath().flowEntries().add(flowEntry);
	}

	return flowPath;
    }
}
