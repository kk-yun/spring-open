package net.onrc.onos.core.topology;

import java.nio.ByteBuffer;
import java.util.Objects;

import net.onrc.onos.core.topology.web.serializers.LinkEventSerializer;
import net.onrc.onos.core.util.Dpid;
import net.onrc.onos.core.util.LinkTuple;
import net.onrc.onos.core.util.PortNumber;
import net.onrc.onos.core.util.SwitchPort;

import static com.google.common.base.Preconditions.checkNotNull;
import org.codehaus.jackson.map.annotate.JsonSerialize;

/**
 * Self-contained Link event Object.
 * <p/>
 * TODO: Rename to match what it is. (Switch/Port/Link/Host)Snapshot?
 * FIXME: Current implementation directly use this object as
 *        Replication message, but should be sending update operation info.
 */

@JsonSerialize(using = LinkEventSerializer.class)
public class LinkEvent extends TopologyElement<LinkEvent> {

    private final LinkTuple id;
    // TODO add LastSeenTime, Capacity if appropriate
    protected static final Double DEFAULT_CAPACITY = Double.POSITIVE_INFINITY;
    private Double capacity = DEFAULT_CAPACITY;

    /**
     * Default constructor for Serializer to use.
     */
    @Deprecated
    protected LinkEvent() {
        id = null;
    }

    /**
     * Creates the Link object.
     *
     * @param id link tuple to identify this link
     */
    public LinkEvent(LinkTuple id) {
        this.id = checkNotNull(id);
    }

    /**
     * Creates the Link object.
     *
     * @param src source SwitchPort
     * @param dst destination SwitchPort
     */
    public LinkEvent(SwitchPort src, SwitchPort dst) {
        this(new LinkTuple(src, dst));
    }

    /**
     * Creates an unfrozen copy of given Object.
     *
     * @param original to make copy of.
     */
    public LinkEvent(LinkEvent original) {
        super(original);
        this.id = original.id;
    }

    // TODO probably want to remove this
    public LinkEvent(Link link) {
        this(link.getLinkTuple());
        // FIXME losing attributes here
    }

    /**
     * Gets a {@link LinkTuple} that identifies this link.
     *
     * @return a LinkTuple representing the Port
     */
    public LinkTuple getLinkTuple() {
        return id;
    }

    /**
     * Gets the source SwitchPort.
     *
     * @return source SwitchPort.
     */
    public SwitchPort getSrc() {
        return getLinkTuple().getSrc();
    }

    /**
     * Gets the destination SwitchPort.
     *
     * @return destination SwitchPort.
     */
    public SwitchPort getDst() {
        return getLinkTuple().getDst();
    }

    /**
     * Gets the link capacity.
     * TODO: What is the unit?
     *
     * @return capacity
     */
    public Double getCapacity() {
        return capacity;
    }

    /**
     * Sets the link capacity.
     * TODO: What is the unit?
     *
     * @param capacity capacity
     */
    void setCapacity(Double capacity) {
        if (isFrozen()) {
            throw new IllegalStateException("Tried to modify frozen instance: " + this);
        }

        this.capacity = capacity;
    }

    public static final int LINKID_BYTES = 2 + PortEvent.PORTID_BYTES * 2;

    public static ByteBuffer getLinkID(Dpid srcDpid, PortNumber srcPortNo,
                                       Dpid dstDpid, PortNumber dstPortNo) {
        return getLinkID(srcDpid.value(), srcPortNo.value(),
                         dstDpid.value(), dstPortNo.value());
    }

    public static ByteBuffer getLinkID(Long srcDpid, Long srcPortNo,
                                       Long dstDpid, Long dstPortNo) {
        return (ByteBuffer) ByteBuffer.allocate(LinkEvent.LINKID_BYTES)
                .putChar('L')
                .put(PortEvent.getPortID(srcDpid, srcPortNo))
                .put(PortEvent.getPortID(dstDpid, dstPortNo)).flip();
    }

    @Override
    public Dpid getOriginDpid() {
        return this.id.getDst().getDpid();
    }

    @Override
    public ByteBuffer getIDasByteBuffer() {
        return getLinkID(getSrc().getDpid(), getSrc().getPortNumber(),
                getDst().getDpid(), getDst().getPortNumber());
    }

    @Override
    public int hashCode() {
        return 31 * super.hashCode() + Objects.hashCode(id);
    }

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

        LinkEvent other = (LinkEvent) o;
        return Objects.equals(this.id, other.id);
    }

    @Override
    public String toString() {
        return "[LinkEvent " + getSrc() + "->" + getDst() + "]";
    }
}
