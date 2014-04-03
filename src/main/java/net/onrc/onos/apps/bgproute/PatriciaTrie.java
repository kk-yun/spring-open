package net.onrc.onos.apps.bgproute;

import java.util.Iterator;
import java.util.NoSuchElementException;

public class PatriciaTrie<V> implements IPatriciaTrie<V> {
    private final byte maskBits[] = {(byte) 0x00, (byte) 0x80, (byte) 0xc0, (byte) 0xe0, (byte) 0xf0,
            (byte) 0xf8, (byte) 0xfc, (byte) 0xfe, (byte) 0xff};

    private int maxPrefixLength;

    private Node top;

    public PatriciaTrie(int maxPrefixLength) {
        this.maxPrefixLength = maxPrefixLength;
    }

    @Override
    public synchronized V put(Prefix prefix, V value) {
        if (prefix == null || value == null) {
            throw new NullPointerException();
        }

        if (prefix.getPrefixLength() > maxPrefixLength) {
            throw new IllegalArgumentException(String.format(
                    "Prefix length %d is greater than max prefix length %d",
                    prefix.getPrefixLength(), maxPrefixLength));
        }

        Node node = top;
        Node match = null;

        while (node != null
                && node.prefix.getPrefixLength() <= prefix.getPrefixLength()
                && key_match(node.prefix.getAddress(), node.prefix.getPrefixLength(), prefix.getAddress(), prefix.getPrefixLength()) == true) {
            if (node.prefix.getPrefixLength() == prefix.getPrefixLength()) {
                /*
                 * Prefix is already in tree. This may be an aggregate node, in which case
                 * we are inserting a new prefix, or it could be an actual node, in which
                 * case we are inserting a new nexthop for the prefix and should return
                 * the old nexthop.
                 */
                V oldValue = node.value;
                node.value = value;
                return oldValue;
            }

            match = node;

            if (bit_check(prefix.getAddress(), node.prefix.getPrefixLength()) == true) {
                node = node.right;
            } else {
                node = node.left;
            }
        }

        Node add = null;

        if (node == null) {
            //add = new Node(p, r);
            add = new Node(prefix);
            add.value = value;

            if (match != null) {
                node_link(match, add);
            } else {
                top = add;
            }
        } else {
            add = node_common(node, prefix.getAddress(), prefix.getPrefixLength());
            if (add == null) {
                //I think this is -ENOMEM?
                //return null;
            }

            if (match != null) {
                node_link(match, add);
            } else {
                top = add;
            }
            node_link(add, node);

            if (add.prefix.getPrefixLength() != prefix.getPrefixLength()) {
                match = add;

                //add = new Node(p, r);
                add = new Node(prefix);
                add.value = value;
                node_link(match, add);
            } else {
                add.value = value;
            }
        }

        //If we added a new Node, there was no previous mapping
        return null;
        //return addReference(add);
    }

    /*exact match*/
    @Override
    public synchronized V lookup(Prefix prefix) {
        if (prefix.getPrefixLength() > maxPrefixLength) {
            return null;
        }

        /*
        Node node = top;

        while (node != null
                && node.prefix.getPrefixLength() <= p.getPrefixLength()
                && key_match(node.prefix.getAddress(), node.prefix.getPrefixLength(), p.getAddress(), p.getPrefixLength()) == true) {
            if (node.prefix.getPrefixLength() == p.getPrefixLength()) {
                //return addReference(node);
                return node.rib;
            }

            if (bit_check(p.getAddress(), node.prefix.getPrefixLength()) == true) {
                node = node.right;
            } else {
                node = node.left;
            }
        }
        */

        Node node = findNode(prefix);

        return node == null ? null : node.value;
    }

    /*closest containing prefix*/
    @Override
    public synchronized V match(Prefix prefix) {
        //TODO
        if (prefix.getPrefixLength() > maxPrefixLength) {
            return null;
        }

        Node closestNode = findClosestNode(prefix);

        return closestNode == null ? null : closestNode.value;
    }

    @Override
    public synchronized boolean remove(Prefix prefix, V value) {
        Node child;
        Node parent;

        if (prefix == null || value == null) {
            return false;
        }

        Node node = findNode(prefix);

        if (node == null || node.isAggregate() || !node.value.equals(value)) {
            //Given <prefix, nexthop> mapping is not in the tree
            return false;
        }

        if (node.left != null && node.right != null) {
            //Remove the RibEntry entry and leave this node as an aggregate node
            //In the future, maybe we should re-evaluate what the aggregate prefix should be?
            //It shouldn't necessarily stay the same.
            //More complicated if the above prefix is also aggregate.
            node.value = null;
            return true;
        }

        if (node.left != null) {
            child = node.left;
        } else {
            child = node.right;
        }

        parent = node.parent;

        if (child != null) {
            child.parent = parent;
        }

        if (parent != null) {
            if (parent.left == node) {
                parent.left = child;
            } else {
                parent.right = child;
            }
        } else {
            top = child;
        }

        /*
         * TODO not sure what to do here. I think this is lazily deleting aggregate nodes,
         * notice that it used to do nothing if it detected both children were not null earlier.
         * But here, what we really should do is reevaluate the aggregate prefix of the parent
         * node (if it is indeed an aggregate). Because at the moment, no aggregate node will ever
         * be removed. BUT, I don't actually think this presents a correctness problem, at
         * least from an external point of view.
         */
        //if (parent != null && parent.refCount == 0) {
        //node_remove(parent);
        //}

        return true;
    }

    @Override
    public Iterator<Entry<V>> iterator() {
        return new PatriciaTrieIterator(top);
    }

    private Node findNode(Prefix prefix) {
        Node node = top;

        while (node != null
                && node.prefix.getPrefixLength() <= prefix.getPrefixLength()
                && key_match(node.prefix.getAddress(), node.prefix.getPrefixLength(), prefix.getAddress(), prefix.getPrefixLength()) == true) {
            if (node.prefix.getPrefixLength() == prefix.getPrefixLength()) {
                //return addReference(node);
                return node;
            }

            if (bit_check(prefix.getAddress(), node.prefix.getPrefixLength()) == true) {
                node = node.right;
            } else {
                node = node.left;
            }
        }

        return null;
    }

    private Node findClosestNode(Prefix prefix) {
        Node node = top;
        Node match = null;

        while (node != null
                && node.prefix.getPrefixLength() <= prefix.getPrefixLength()
                && key_match(node.prefix.getAddress(), node.prefix.getPrefixLength(), prefix.getAddress(), prefix.getPrefixLength()) == true) {
            if (!node.isAggregate()) {
                match = node;
            }

            if (bit_check(prefix.getAddress(), node.prefix.getPrefixLength()) == true) {
                node = node.right;
            } else {
                node = node.left;
            }
        }

        return match;
    }

    /*
     * Receives a 1-based bit index
     * Returns a 1-based byte index
     * eg. (0 => 1), 1 => 1, 8 => 1, 9 => 2, 17 => 3
     */
    private int getByteContainingBit(int bitNumber) {
        return Math.max((bitNumber + 7) / 8, 1);
    }

    private boolean key_match(byte[] key1, int key1_len, byte[] key2, int key2_len) {
        //int offset;
        //int shift;

        if (key1_len > key2_len) {
            return false;
        }

        int offset = (Math.min(key1_len, key2_len)) / 8;
        int shift = (Math.min(key1_len, key2_len)) % 8;

        if (shift != 0) {
            if ((maskBits[shift] & (key1[offset] ^ key2[offset])) != 0) {
                return false;
            }
        }

        while (offset != 0) {
            offset--;
            if (key1[offset] != key2[offset]) {
                return false;
            }
        }
        return true;
    }

    private boolean bit_check(byte[] key, int key_bits) {
        int offset = key_bits / 8;
        int shift = 7 - (key_bits % 8);
        int bit = key[offset] & 0xff;

        bit >>= shift;

        if ((bit & 1) == 1) {
            return true;
        } else {
            return false;
        }
    }

    private void node_link(Node node, Node add) {
        boolean bit = bit_check(add.prefix.getAddress(), node.prefix.getPrefixLength());

        if (bit == true) {
            node.right = add;
        } else {
            node.left = add;
        }
        add.parent = node;
    }

    private Node node_common(Node node, byte[] key, int key_bits) {
        int i;
        int limit = Math.min(node.prefix.getPrefixLength(), key_bits) / 8;

        for (i = 0; i < limit; i++) {
            if (node.prefix.getAddress()[i] != key[i]) {
                break;
            }
        }

        int common_len = i * 8;
        int boundary = 0;

        if (common_len != key_bits) {
            byte diff = (byte) (node.prefix.getAddress()[i] ^ key[i]);
            byte mask = (byte) 0x80;
            int shift_mask = 0;

            while (common_len < key_bits && ((mask & diff) == 0)) {
                boundary = 1;

                shift_mask = (mask & 0xff);
                shift_mask >>= 1;
                mask = (byte) shift_mask;

                common_len++;
            }
        }

        //Node add = new Node(null, common_len, maxKeyOctets);
        //if (add == null)
        //Another -ENOMEM;
        //return null;

        //Creating a new Prefix with a prefix length of common_len
        //Bits are copied from node's up until the common_len'th bit
        //RibEntry is null, because this is an aggregate prefix - it's not
        //actually been added to the trie.

        byte[] newPrefix = new byte[getByteContainingBit(maxPrefixLength)];

        int j;
        for (j = 0; j < i; j++)
            newPrefix[j] = node.prefix.getAddress()[j];

        if (boundary != 0)
            newPrefix[j] = (byte) (node.prefix.getAddress()[j] & maskBits[common_len % 8]);

        //return new Node(new Prefix(newPrefix, common_len), null);
        return new Node(new Prefix(newPrefix, common_len));
        //return add;
    }

    private class Node {
        public Node parent = null;
        public Node left = null;
        public Node right = null;

        public final Prefix prefix;
        public V value;

        //public Node(Prefix p, RibEntry r) {
        //      this.prefix = p;
        //      this.rib = r;
        //}
        public Node(Prefix p) {
            this.prefix = p;
        }

        public boolean isAggregate() {
            return value == null;
        }

        public Entry<V> getEntry() {
            return new PatriciaTrieEntry(prefix, value);
        }
    }

    private class PatriciaTrieEntry implements Entry<V> {
        private Prefix prefix;
        private V value;

        public PatriciaTrieEntry(Prefix prefix, V value) {
            this.prefix = prefix;
            this.value = value;
        }

        @Override
        public Prefix getPrefix() {
            return prefix;
        }

        @Override
        public V getValue() {
            return value;
        }
    }

    private class PatriciaTrieIterator implements Iterator<Entry<V>> {

        private Node current;
        private boolean started = false;

        public PatriciaTrieIterator(Node start) {
            current = start;

            //If the start is an aggregate node fast forward to find the next valid node
            if (current != null && current.isAggregate()) {
                current = findNext(current);
            }
        }

        @Override
        public boolean hasNext() {
            if (current == null) {
                return false;
            }

            if (!started) {
                return true;
            }

            return findNext(current) != null;
        }

        @Override
        public Entry<V> next() {
            if (current == null) {
                throw new NoSuchElementException();
            }

            if (!started) {
                started = true;
                return current.getEntry();
            }

            current = findNext(current);
            if (current == null) {
                throw new NoSuchElementException();
            }

            return current.getEntry();
        }

        @Override
        public void remove() {
            // TODO This could be implemented, if it were needed
            throw new NoSuchElementException();
        }

        private Node findNext(Node node) {
            Node next = null;

            if (node.left != null) {
                next = node.left;
                //addReference(next);
                //delReference(node);
                //return next;
            } else if (node.right != null) {
                next = node.right;
                //addReference(next);
                //delReference(node);
                //return next;
            } else {
                //Node start = node;
                while (node.parent != null) {
                    if (node.parent.left == node && node.parent.right != null) {
                        next = node.parent.right;
                        //addReference(next);
                        //delReference(start);
                        //return next;
                        break;
                    }
                    node = node.parent;
                }
            }

            if (next == null) {
                return null;
            }

            //If the node doesn't have a value, it's not an actual node, it's an artifically
            //inserted aggregate node. We don't want to return these to the user.
            if (next.isAggregate()) {
                return findNext(next);
            }

            return next;
        }
    }
}