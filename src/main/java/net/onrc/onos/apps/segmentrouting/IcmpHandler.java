package net.onrc.onos.apps.segmentrouting;

import java.util.ArrayList;
import java.util.List;

import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.util.MACAddress;
import net.onrc.onos.api.packet.IPacketListener;
import net.onrc.onos.api.packet.IPacketService;
import net.onrc.onos.core.flowprogrammer.IFlowPusherService;
import net.onrc.onos.core.packet.Ethernet;
import net.onrc.onos.core.packet.ICMP;
import net.onrc.onos.core.packet.IPv4;
import net.onrc.onos.core.topology.ITopologyService;
import net.onrc.onos.core.topology.MutableTopology;
import net.onrc.onos.core.topology.Port;
import net.onrc.onos.core.topology.Switch;
import net.onrc.onos.core.util.SwitchPort;

import org.json.JSONArray;
import org.json.JSONException;
import org.projectfloodlight.openflow.protocol.OFFactory;
import org.projectfloodlight.openflow.protocol.OFMatchV3;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFOxmList;
import org.projectfloodlight.openflow.protocol.OFPacketOut;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.instruction.OFInstruction;
import org.projectfloodlight.openflow.protocol.oxm.OFOxmEthDst;
import org.projectfloodlight.openflow.protocol.oxm.OFOxmEthSrc;
import org.projectfloodlight.openflow.protocol.oxm.OFOxmEthType;
import org.projectfloodlight.openflow.protocol.oxm.OFOxmInPort;
import org.projectfloodlight.openflow.protocol.oxm.OFOxmIpv4DstMasked;
import org.projectfloodlight.openflow.protocol.oxm.OFOxmMplsLabel;
import org.projectfloodlight.openflow.protocol.oxm.OFOxmVlanVid;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFBufferId;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.OFVlanVidMatch;
import org.projectfloodlight.openflow.types.TableId;
import org.projectfloodlight.openflow.types.U32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IcmpHandler implements IPacketListener {

    private SegmentRoutingManager srManager;
    private IFloodlightProviderService floodlightProvider;
    private MutableTopology mutableTopology;
    private IPacketService packetService;
    private ITopologyService topologyService;
    private static final Logger log = LoggerFactory
            .getLogger(IcmpHandler.class);

    private IFlowPusherService flowPusher;
    private boolean controllerPortAllowed = false;

    private static final int TABLE_VLAN = 0;
    private static final int TABLE_TMAC = 1;
    private static final int TABLE_IPv4_UNICAST = 2;
    private static final int TABLE_MPLS = 3;
    private static final int TABLE_META = 4;
    private static final int TABLE_ACL = 5;

    private static final short MAX_PRIORITY = (short) 0xffff;
    private static final short SLASH_24_PRIORITY = (short) 0xfff0;
    private static final short SLASH_16_PRIORITY = (short) 0xff00;
    private static final short SLASH_8_PRIORITY = (short) 0xf000;
    private static final short MIN_PRIORITY = 0x0;

    private static final int ICMP_TYPE_ECHO = 0x08;
    private static final int ICMP_TYPE_REPLY = 0x00;


    public IcmpHandler(FloodlightModuleContext context, SegmentRoutingManager manager) {

        this.floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
        this.flowPusher = context.getServiceImpl(IFlowPusherService.class);
        this.packetService = context.getServiceImpl(IPacketService.class);
        this.topologyService = context.getServiceImpl(ITopologyService.class);
        this.mutableTopology = topologyService.getTopology();

        this.srManager = manager;

        packetService.registerPacketListener(this);

    }

    @Override
    public void receive(Switch sw, Port inPort, Ethernet payload) {

        if (payload.getEtherType() == Ethernet.TYPE_IPV4) {

            IPv4 ipv4 = (IPv4)payload.getPayload();

            if (ipv4.getProtocol() == IPv4.PROTOCOL_ICMP) {
                int destinationAddress = ipv4.getDestinationAddress();

                // Check if it is ICMP request to the switch
                String switchIpAddressSlash = sw.getStringAttribute("routerIp");
                if (switchIpAddressSlash != null) {
                    String switchIpAddressStr = switchIpAddressSlash.substring(0, switchIpAddressSlash.indexOf('/'));
                    IPv4Address switchIpAddress = IPv4Address.of(switchIpAddressStr);

                    if (((ICMP)ipv4.getPayload()).getIcmpType() == ICMP_TYPE_ECHO &&
                            destinationAddress == switchIpAddress.getInt()) {
                        sendICMPResponse(sw, inPort, payload);
                        return;
                    }
                }


                // Check if the destination is any host known to TopologyService
                for (net.onrc.onos.core.topology.Host host: mutableTopology.getHosts()) {
                    IPv4Address hostIpAddress = IPv4Address.of(host.getIpAddress());
                    if (hostIpAddress != null && hostIpAddress.getInt() == destinationAddress) {
                        byte[] destinationMacAddress = host.getMacAddress().toBytes();
                        addRouteToHost(sw, destinationAddress, destinationMacAddress);
                        return;
                    }
                }
            }

        }

    }



    /**
     * Send ICMP reply back
     *
     * @param sw Switch
     * @param inPort Port the ICMP packet is forwarded from
     * @param icmpRequest the ICMP request to handle
     * @param destinationAddress destination address to send ICMP response to
     */
    private void sendICMPResponse(Switch sw, Port inPort, Ethernet icmpRequest) {

        Ethernet icmpReplyEth = new Ethernet();

        IPv4 icmpRequestIpv4 = (IPv4) icmpRequest.getPayload();
        IPv4 icmpReplyIpv4 = new IPv4();
        int destAddress = icmpRequestIpv4.getDestinationAddress();
        icmpReplyIpv4.setDestinationAddress(icmpRequestIpv4.getSourceAddress());
        icmpReplyIpv4.setSourceAddress(destAddress);
        icmpReplyIpv4.setTtl((byte)64);
        icmpReplyIpv4.setChecksum((short)0);


        ICMP icmpReply = (ICMP)icmpRequestIpv4.getPayload().clone();
        icmpReply.setIcmpCode((byte)0x00);
        icmpReply.setIcmpType((byte) ICMP_TYPE_REPLY);
        icmpReply.setChecksum((short)0);

        icmpReplyIpv4.setPayload(icmpReply);

        icmpReplyEth.setPayload(icmpReplyIpv4);
        icmpReplyEth.setEtherType(Ethernet.TYPE_IPV4);
        icmpReplyEth.setDestinationMACAddress(icmpRequest.getSourceMACAddress());
        icmpReplyEth.setSourceMACAddress(icmpRequest.getDestinationMACAddress());

        sendPacketOut(sw, icmpReplyEth, new SwitchPort(sw.getDpid(), inPort.getPortNumber()), false);

        log.debug("Send an ICMP response {}", icmpReplyIpv4.toString());

    }

    /**
     * Send PACKET_OUT message with actions
     * If switches support OFPP_TABLE action, it sends out packet to TABLE port
     * Otherwise, it sends the packet to the port the packet came from
     * (in this case, MPLS label is added if the packet needs go through transit switches)
     *
     * @param sw  Switch the packet came from
     * @param packet Ethernet packet to send
     * @param switchPort port to send the packet
     */
    private void sendPacketOut(Switch sw, Ethernet packet, SwitchPort switchPort, boolean supportOfppTable) {

        boolean sameSubnet = false;
        IOFSwitch ofSwitch = floodlightProvider.getMasterSwitch(sw.getDpid().value());
        OFFactory factory = ofSwitch.getFactory();

        List<OFAction> actions = new ArrayList<>();

        // If OFPP_TABLE action is not supported in the switch, MPLS label needs to be set
        // if the packet needs to be delivered crossing switches
        if (!supportOfppTable) {
            // Check if the destination is the host attached to the switch
            int destinationAddress = ((IPv4)packet.getPayload()).getDestinationAddress();
            for (net.onrc.onos.core.topology.Host host: mutableTopology.getHosts(switchPort)) {
                IPv4Address hostIpAddress = IPv4Address.of(host.getIpAddress());
                if (hostIpAddress != null && hostIpAddress.getInt() == destinationAddress) {
                    sameSubnet = true;
                    break;
                }
            }

            // If the destination host is not attached in the switch, add MPLS label
            if (!sameSubnet) {

                IPv4Address targetAddress = IPv4Address.of(((IPv4)packet.getPayload()).getDestinationAddress());
                int mplsLabel = getMplsLabelFromConfig(targetAddress);
                if (mplsLabel > 0) {
                    OFAction pushlabel = factory.actions().pushMpls(EthType.MPLS_UNICAST);
                    OFOxmMplsLabel l = factory.oxms()
                            .mplsLabel(U32.of(mplsLabel));
                    OFAction setlabelid = factory.actions().buildSetField()
                            .setField(l).build();
                    OFAction copyTtlOut = factory.actions().copyTtlOut();
                    actions.add(pushlabel);
                    actions.add(setlabelid);
                    actions.add(copyTtlOut);
                }
            }

            OFAction outport = factory.actions().output(OFPort.of(switchPort.getPortNumber().shortValue()), Short.MAX_VALUE);
            actions.add(outport);
        }
        // If OFPP_TABLE action is supported, first set a rule to allow packet from CONTROLLER port.
        // Then, send the packet to the table port
        else {
            if (!controllerPortAllowed) {
                addControlPortInVlanTable(sw);
                controllerPortAllowed = true;
            }
            OFAction outport = factory.actions().output(OFPort.TABLE, Short.MAX_VALUE);
            actions.add(outport);
        }

        OFPacketOut po = factory.buildPacketOut()
                .setData(packet.serialize())
                .setActions(actions)
                .build();

        flowPusher.add(sw.getDpid(), po);
    }

    /**
     * Get MPLS label for the target address from the network config file
     *
     * @param targetAddress - IP address of the target host
     * @return MPLS label of the switch to send packets to the target address
     */
    private int getMplsLabelFromConfig(IPv4Address targetAddress) {

        int mplsLabel = -1;

        for (Switch sw: mutableTopology.getSwitches()) {

            String subnets = sw.getStringAttribute("subnets");
            try {
                JSONArray arry = new JSONArray(subnets);
                for (int i = 0; i < arry.length(); i++) {
                    String subnetIp = (String) arry.getJSONObject(i).get("subnetIp");
                    if (srManager.netMatch(subnetIp, targetAddress.toString())) {
                        String mplsLabelStr = sw.getStringAttribute("nodeSid");
                        if (mplsLabelStr != null)
                            mplsLabel = Integer.parseInt(mplsLabelStr);
                    }
                }
            } catch (JSONException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }

        return mplsLabel;
    }

    /**
     * Add routing rules to forward packets to known hosts
     *
     * @param sw Switch
     * @param hostIp Host IP address to forwards packets to
     */
    private void addRouteToHost(Switch sw, int destinationAddress, byte[] destinationMacAddress) {

        IOFSwitch ofSwitch = floodlightProvider.getMasterSwitch(sw.getDpid().value());
        OFFactory factory = ofSwitch.getFactory();


        OFOxmEthType ethTypeIp = factory.oxms()
                .ethType(EthType.IPv4);
        OFOxmIpv4DstMasked ipPrefix = factory.oxms()
                .ipv4DstMasked(
                        IPv4Address.of(destinationAddress),
                        IPv4Address.NO_MASK); // host addr should be /32
        OFOxmList oxmListSlash32 = OFOxmList.of(ethTypeIp, ipPrefix);
        OFMatchV3 match = factory.buildMatchV3()
                .setOxmList(oxmListSlash32).build();
        OFAction setDmac = null;
        OFOxmEthDst dmac = factory.oxms()
                .ethDst(MacAddress.of(destinationMacAddress));
        setDmac = factory.actions().buildSetField()
                .setField(dmac).build();

        OFAction decTtl = factory.actions().decNwTtl();

        // Set the source MAC address with the switch MAC address
        String switchMacAddress = sw.getStringAttribute("routerMac");
        OFOxmEthSrc srcAddr = factory.oxms().ethSrc(MacAddress.of(switchMacAddress));
        OFAction setSA = factory.actions().buildSetField()
                .setField(srcAddr).build();

        List<OFAction> actionList = new ArrayList<OFAction>();
        actionList.add(setDmac);
        actionList.add(decTtl);
        actionList.add(setSA);


        /* TODO : need to check the config file for all packets
        String subnets = sw.getStringAttribute("subnets");
        try {
            JSONArray arry = new JSONArray(subnets);
            for (int i = 0; i < arry.length(); i++) {
                String subnetIp = (String) arry.getJSONObject(i).get("subnetIp");
                int portNo = (int) arry.getJSONObject(i).get("portNo");

                if (netMatch(subnetIp, IPv4Address.of(hostIp.getDestinationAddress()).toString())) {
                    OFAction out = factory.actions().buildOutput()
                            .setPort(OFPort.of(portNo)).build();
                    actionList.add(out);
                }
            }
        } catch (JSONException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        */

        // Set output port
        net.onrc.onos.core.topology.Host host = mutableTopology.getHostByMac(MACAddress.valueOf(destinationMacAddress));
        if (host != null) {
            for (Port port: host.getAttachmentPoints()) {
                OFAction out = factory.actions().buildOutput()
                                .setPort(OFPort.of(port.getPortNumber().shortValue())).build();
                actionList.add(out);
            }
        }

        OFInstruction writeInstr = factory.instructions().buildWriteActions()
                .setActions(actionList).build();

        List<OFInstruction> instructions = new ArrayList<OFInstruction>();
        instructions.add(writeInstr);

        OFMessage myIpEntry = factory.buildFlowAdd()
                .setTableId(TableId.of(TABLE_IPv4_UNICAST))
                .setMatch(match)
                .setInstructions(instructions)
                .setPriority(MAX_PRIORITY)
                .setBufferId(OFBufferId.NO_BUFFER)
                .setIdleTimeout(0)
                .setHardTimeout(0)
                //.setXid(getNextTransactionId())
                .build();

        log.debug("Sending 'Routing information' OF message to the switch {}.", sw.getDpid().toString());

        flowPusher.add(sw.getDpid(), myIpEntry);

    }

    /**
     * Add a new rule to VLAN table to forward packets from any port to the next table
     * It is required to forward packets from controller to pipeline
     *
     * @param sw  Switch the packet came from
     */
    private void addControlPortInVlanTable(Switch sw) {

        IOFSwitch ofSwitch = floodlightProvider.getMasterSwitch(sw.getDpid().value());
        OFFactory factory = ofSwitch.getFactory();

        OFOxmInPort oxp = factory.oxms().inPort(OFPort.CONTROLLER);
        OFOxmVlanVid oxv = factory.oxms()
                .vlanVid(OFVlanVidMatch.UNTAGGED);
        OFOxmList oxmList = OFOxmList.of(oxv);

        /* Cqpd switch does not seems to support CONTROLLER port as in_port match rule */
        //OFOxmList oxmList = OFOxmList.of(oxp, oxv);

        OFMatchV3 match = factory.buildMatchV3()
                .setOxmList(oxmList)
                .build();

        OFInstruction gotoTbl = factory.instructions().buildGotoTable()
                .setTableId(TableId.of(TABLE_TMAC)).build();
        List<OFInstruction> instructions = new ArrayList<OFInstruction>();
        instructions.add(gotoTbl);
        OFMessage flowEntry = factory.buildFlowAdd()
                .setTableId(TableId.of(TABLE_VLAN))
                .setMatch(match)
                .setInstructions(instructions)
                .setPriority(1000) // does not matter - all rules
                                   // exclusive
                .setBufferId(OFBufferId.NO_BUFFER)
                .setIdleTimeout(0)
                .setHardTimeout(0)
                //.setXid(getNextTransactionId())
                .build();

        flowPusher.add(sw.getDpid(), flowEntry);;
        log.debug("Adding a new vlan-rules in sw {}", sw.getDpid());

    }

}