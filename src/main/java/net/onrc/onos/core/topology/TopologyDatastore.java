package net.onrc.onos.core.topology;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import net.onrc.onos.core.datastore.DataStoreClient;
import net.onrc.onos.core.datastore.IKVClient;
import net.onrc.onos.core.datastore.topology.KVDevice;
import net.onrc.onos.core.datastore.topology.KVLink;
import net.onrc.onos.core.datastore.topology.KVPort;
import net.onrc.onos.core.datastore.topology.KVPort.STATUS;
import net.onrc.onos.core.datastore.topology.KVSwitch;
import net.onrc.onos.core.datastore.utils.KVObject;
import net.onrc.onos.core.datastore.utils.KVObject.WriteOp;
import net.onrc.onos.core.util.SwitchPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Contains methods which write topology events into the key-value data store.
 */
public class TopologyDatastore {
    private static final Logger log = LoggerFactory.getLogger(TopologyDatastore.class);

    /**
     * Add a switch to the database.
     *
     * @param sw         the switch to add.
     * @param portDataEntries the corresponding switch ports to add.
     * @return true on success, otherwise false.
     */
    public boolean addSwitch(SwitchData sw,
                             Collection<PortData> portDataEntries) {
        log.debug("Adding switch {}", sw);
        ArrayList<WriteOp> groupOp = new ArrayList<>();

        KVSwitch rcSwitch = new KVSwitch(sw.getDpid());
        rcSwitch.setStatus(KVSwitch.STATUS.ACTIVE);

        IKVClient client = DataStoreClient.getClient();

        // XXX Is ForceCreating Switch on DB OK here?
        // If ForceCreating, who ever is calling this method needs
        // to assure that DPID is unique cluster-wide, etc.
        groupOp.add(rcSwitch.forceCreateOp(client));

        for (PortData portData : portDataEntries) {
            KVPort rcPort = new KVPort(sw.getDpid(), portData.getPortNumber());
            rcPort.setStatus(KVPort.STATUS.ACTIVE);

            groupOp.add(rcPort.forceCreateOp(client));
        }

        boolean failed = KVObject.multiWrite(groupOp);

        if (failed) {
            log.error("Adding Switch {} and its ports failed.", sw.getDpid());
            for (WriteOp op : groupOp) {
                log.debug("Operation:{} for {} - Result:{}", op.getOperation(), op.getObject(), op.getStatus());

                // If we changed the operation from ForceCreate to
                // Conditional operation (Create/Update) then we should retry here.
            }
        }
        return !failed;
    }

    /**
     * Update a switch as inactive in the database.
     *
     * @param sw         the switch to update.
     * @param portDataEntries the corresponding switch ports to update.
     * @return true on success, otherwise false.
     */
    public boolean deactivateSwitch(SwitchData sw,
                                    Collection<PortData> portDataEntries) {
        log.debug("Deactivating switch {}", sw);
        KVSwitch rcSwitch = new KVSwitch(sw.getDpid());

        IKVClient client = DataStoreClient.getClient();

        List<WriteOp> groupOp = new ArrayList<>();
        rcSwitch.setStatus(KVSwitch.STATUS.INACTIVE);

        groupOp.add(rcSwitch.forceCreateOp(client));

        for (PortData portData : portDataEntries) {
            KVPort rcPort = new KVPort(sw.getDpid(), portData.getPortNumber());
            rcPort.setStatus(KVPort.STATUS.INACTIVE);

            groupOp.add(rcPort.forceCreateOp(client));
        }

        boolean failed = KVObject.multiWrite(groupOp);

        return !failed;
    }

    /**
     * Add a port to the database.
     *
     * @param port the port to add.
     * @return true on success, otherwise false.
     */
    public boolean addPort(PortData port) {
        log.debug("Adding port {}", port);

        KVPort rcPort = new KVPort(port.getDpid(), port.getPortNumber());
        rcPort.setStatus(KVPort.STATUS.ACTIVE);
        rcPort.forceCreate();
        // TODO add description into KVPort
        //rcPort.setDescription(port.getDescription());

        return true;
    }

    /**
     * Update a port as inactive in the database.
     *
     * @param port the port to update.
     * @return true on success, otherwise false.
     */
    public boolean deactivatePort(PortData port) {
        log.debug("Deactivating port {}", port);

        KVPort rcPort = new KVPort(port.getDpid(), port.getPortNumber());
        rcPort.setStatus(STATUS.INACTIVE);

        rcPort.forceCreate();

        return true;
    }

    /**
     * Add a link to the database.
     *
     * @param link the link to add.
     * @return true on success, otherwise false.
     */
    public boolean addLink(LinkData link) {
        log.debug("Adding link {}", link);

        KVLink rcLink = new KVLink(link.getSrc().getDpid(),
                link.getSrc().getPortNumber(),
                link.getDst().getDpid(),
                link.getDst().getPortNumber());

        // XXX This method is called only by discovery,
        // which means what we are trying to write currently is the truth
        // so we can force write here
        //
        // TODO: We need to check for errors
        rcLink.setStatus(KVLink.STATUS.ACTIVE);
        rcLink.forceCreate();

        return true;                    // Success
    }

    public boolean removeLink(LinkData linkData) {
        log.debug("Removing link {}", linkData);

        KVLink rcLink = new KVLink(linkData.getSrc().getDpid(), linkData.getSrc().getPortNumber(),
                linkData.getDst().getDpid(), linkData.getDst().getPortNumber());
        rcLink.forceDelete();

        return true;
    }

    /**
     * Add a device to the database.
     *
     * @param device the device to add.
     * @return true on success, otherwise false.
     */
    public boolean addHost(HostData device) {
        log.debug("Adding host into DB. mac {}", device.getMac());

        KVDevice rcDevice = new KVDevice(device.getMac().toBytes());

        for (SwitchPort sp : device.getAttachmentPoints()) {
            byte[] portId = KVPort.getPortID(sp.getDpid(), sp.getPortNumber());
            rcDevice.addPortId(portId);
        }

        rcDevice.forceCreate();

        return true;
    }

    /**
     * Remove a device from the database.
     *
     * @param device the device to remove.
     * @return true on success, otherwise false.
     */
    public boolean removeHost(HostData device) {
        log.debug("Removing host into DB. mac {}", device.getMac());

        KVDevice rcDevice = new KVDevice(device.getMac().toBytes());
        rcDevice.forceDelete();

        return true;
    }
}
