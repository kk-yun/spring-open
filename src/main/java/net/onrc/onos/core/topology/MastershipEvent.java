package net.onrc.onos.core.topology;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Objects;

import net.floodlightcontroller.core.IFloodlightProviderService.Role;
import net.onrc.onos.core.topology.web.serializers.MastershipEventSerializer;
import net.onrc.onos.core.util.Dpid;
import net.onrc.onos.core.util.OnosInstanceId;

import org.codehaus.jackson.map.annotate.JsonSerialize;

/**
 * Self-contained Switch Mastership event Object.
 * <p/>
 * TODO: Rename to match what it is. (Switch/Port/Link/Host)Snapshot?
 * FIXME: Current implementation directly use this object as
 *        Replication message, but should be sending update operation info.
 */
@JsonSerialize(using = MastershipEventSerializer.class)
public class MastershipEvent extends TopologyElement<MastershipEvent> {
    private final Dpid dpid;
    private final OnosInstanceId onosInstanceId;
    private final Role role;

    /**
     * Default constructor for Serializer to use.
     */
    @Deprecated
    protected MastershipEvent() {
        dpid = null;
        onosInstanceId = null;
        role = Role.SLAVE;              // Default role is SLAVE
    }

    /**
     * Constructor for given switch DPID, ONOS Instance ID, and ONOS instance
     * role for the switch.
     *
     * @param dpid the switch DPID
     * @param onosInstanceId the ONOS Instance ID
     * @param role the ONOS instance role for the switch
     */
    public MastershipEvent(Dpid dpid, OnosInstanceId onosInstanceId,
                           Role role) {
        this.dpid = checkNotNull(dpid);
        this.onosInstanceId = checkNotNull(onosInstanceId);
        this.role = role;
    }

    /**
     * Copy constructor.
     * <p>
     * Creates an unfrozen copy of the given Switch MastershipEvent object.
     *
     * @param original the object to make copy of
     */
    public MastershipEvent(MastershipEvent original) {
        super(original);
        this.dpid = original.dpid;
        this.onosInstanceId = original.onosInstanceId;
        this.role = original.role;
    }

    /**
     * Gets the Switch DPID.
     *
     * @return the Switch DPID
     */
    public Dpid getDpid() {
        return dpid;
    }

    /**
     * Gets the ONOS Instance ID.
     *
     * @return the ONOS Instance ID
     */
    public OnosInstanceId getOnosInstanceId() {
        return onosInstanceId;
    }

    /**
     * Gets the ONOS Controller Role for the Switch.
     *
     * @return the ONOS Controller Role for the Switch
     */
    public Role getRole() {
        return role;
    }

    @Override
    public Dpid getOriginDpid() {
        return this.dpid;
    }

    @Override
    public ByteBuffer getIDasByteBuffer() {
        String keyStr = "M" + getDpid() + "@" + getOnosInstanceId();
        byte[] id = keyStr.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.wrap(id);
        return buf;
    }

    @Override
    public int hashCode() {
        return Objects.hash(dpid, onosInstanceId);
    }

    /**
     * Compares two MastershipEvent objects.
     * MastershipEvent objects are equal if they have same DPID and same
     * ONOS Instance ID.
     *
     * @param o another object to compare to this
     * @return true if equal, false otherwise
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        // Compare attributes
        if (!super.equals(o)) {
            return false;
        }

        MastershipEvent other = (MastershipEvent) o;
        return Objects.equals(dpid, other.dpid) &&
            Objects.equals(onosInstanceId, other.onosInstanceId);
    }

    /**
     * Comparator to bring {@link MastershipEvent} with MASTER role up front.
     * <p>
     * Note: expected to be used against Collection of MastershipEvents
     * about same Dpid.
     */
    public static final class MasterFirstComparator
                implements Comparator<MastershipEvent>, Serializable {
        @Override
        public int compare(MastershipEvent o1, MastershipEvent o2) {
            // MastershipEvent for same ONOS Instance
            //  => treat as equal regardless of Role
            // Note: MastershipEvent#equals() does not use Role
            if (o1.equals(o2)) {
                return 0;
            }

            // MASTER Role instance is considered smaller.
            // (appears earlier in SortedSet iteration, etc.)
            if (o1.getRole() == Role.MASTER) {
                return -1;
            }
            if (o2.getRole() == Role.MASTER) {
                return 1;
            }

            return o1.getOnosInstanceId().toString()
                    .compareTo(o2.getOnosInstanceId().toString());
        }
    }

    @Override
    public String toString() {
        return "[MastershipEvent " + getDpid() + "@" + getOnosInstanceId() +
            "->" + getRole() + "]";
    }
}
