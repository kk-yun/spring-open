package net.onrc.onos.core.datastore.topology;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.onrc.onos.core.datastore.DataStoreClient;
import net.onrc.onos.core.datastore.IKVTable;
import net.onrc.onos.core.datastore.ObjectDoesntExistException;
import net.onrc.onos.core.datastore.ObjectExistsException;
import net.onrc.onos.core.datastore.WrongVersionException;
import net.onrc.onos.core.datastore.topology.KVSwitch.STATUS;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KVSwitchTest {

    private static final Logger log = LoggerFactory.getLogger(KVSwitchTest.class);

    private static final String NAMESPACE = UUID.randomUUID().toString();

    IKVTable switchTable;
    static final Long DPID1 = 0x1L;
    KVSwitch sw1;

    @Before
    public void setUp() throws Exception {
        switchTable = DataStoreClient.getClient().getTable(NAMESPACE + KVSwitch.SWITCH_TABLE_SUFFIX);
        sw1 = new KVSwitch(DPID1, NAMESPACE);
    }

    @After
    public void tearDown() throws Exception {
        DataStoreClient.getClient().dropTable(switchTable);
    }

    public KVSwitch assertSwitchInDataStore(final Long dpid, final STATUS status) {
        try {
            final KVSwitch sw = new KVSwitch(dpid, NAMESPACE);
            sw.read();
            assertNotEquals(DataStoreClient.getClient().getVersionNonexistant(), sw.getVersion());
            assertEquals(dpid, sw.getDpid());
            assertEquals(status, sw.getStatus());
            return sw;
        } catch (ObjectDoesntExistException e) {
            fail("Switch was not written to datastore");
        }
        return null;
    }

    public void assertSwitchNotInDataStore(final Long dpid) {
        final KVSwitch sw = new KVSwitch(dpid, NAMESPACE);
        try {
            sw.read();
            fail("Switch was not supposed to be there in datastore");
        } catch (ObjectDoesntExistException e) {
            log.debug("Exception thrown as expected", e);
        }
    }

    @Test
    public void testGetAllSwitches() throws ObjectExistsException {
        final int numSwitches = 100;
        Map<Long, KVSwitch> expected = new HashMap<>();
        for (long dpid = 1; dpid <= numSwitches; ++dpid) {
            KVSwitch sw = new KVSwitch(dpid, NAMESPACE);
            sw.setStatus(STATUS.ACTIVE);
            sw.create();
            assertNotEquals(DataStoreClient.getClient().getVersionNonexistant(), sw.getVersion());
            expected.put(sw.getDpid(), sw);
        }

        Iterable<KVSwitch> switches = KVSwitch.getAllSwitches(NAMESPACE);

        for (KVSwitch sw : switches) {
            KVSwitch expectedSw = expected.get(sw.getDpid());
            assertNotNull(expectedSw);
            assertEquals(expectedSw.getDpid(), sw.getDpid());
            assertEquals(expectedSw.getStatus(), sw.getStatus());
            assertEquals(expectedSw.getVersion(), sw.getVersion());

            assertArrayEquals(expectedSw.getKey(), sw.getKey());
        }
    }

    @Test
    public void testCreate() throws ObjectExistsException {
        sw1.setStatus(STATUS.ACTIVE);
        sw1.create();
        assertNotEquals(DataStoreClient.getClient().getVersionNonexistant(), sw1.getVersion());

        assertEquals(DPID1, sw1.getDpid());
        assertEquals(STATUS.ACTIVE, sw1.getStatus());

        assertSwitchInDataStore(DPID1, STATUS.ACTIVE);
    }

    @Test(expected = ObjectExistsException.class)
    public void testCreateFailAlreadyExist() throws ObjectExistsException {
        // setup pre-existing Switch
        KVSwitch sw = new KVSwitch(DPID1, NAMESPACE);
        sw.forceCreate();
        assertNotEquals(DataStoreClient.getClient().getVersionNonexistant(), sw.getVersion());
        assertSwitchInDataStore(DPID1, STATUS.INACTIVE);

        sw1.setStatus(STATUS.ACTIVE);
        sw1.create();
        fail("Should have thrown an exception");
    }

    @Test
    public void testForceCreate() {
        // setup pre-existing Switch
        KVSwitch sw = new KVSwitch(DPID1, NAMESPACE);
        sw.forceCreate();
        assertNotEquals(DataStoreClient.getClient().getVersionNonexistant(), sw.getVersion());
        assertSwitchInDataStore(DPID1, STATUS.INACTIVE);


        sw1.setStatus(STATUS.ACTIVE);
        sw1.forceCreate();
        assertNotEquals(DataStoreClient.getClient().getVersionNonexistant(), sw1.getVersion());

        assertEquals(DPID1, sw1.getDpid());
        assertEquals(STATUS.ACTIVE, sw1.getStatus());
        assertSwitchInDataStore(DPID1, STATUS.ACTIVE);
    }

    @Test
    public void testRead() throws ObjectDoesntExistException {
        // setup pre-existing Switch
        KVSwitch sw = new KVSwitch(DPID1, NAMESPACE);
        sw.setStatus(STATUS.ACTIVE);
        sw.forceCreate();
        assertNotEquals(DataStoreClient.getClient().getVersionNonexistant(), sw.getVersion());
        assertSwitchInDataStore(DPID1, STATUS.ACTIVE);

        sw1.read();
        assertNotEquals(DataStoreClient.getClient().getVersionNonexistant(), sw1.getVersion());
        assertEquals(sw.getVersion(), sw1.getVersion());
        assertEquals(DPID1, sw1.getDpid());
        assertEquals(STATUS.ACTIVE, sw1.getStatus());
    }

    @Test(expected = ObjectDoesntExistException.class)
    public void testReadFailNoExist() throws ObjectDoesntExistException {

        sw1.read();
        fail("Should have thrown an exception");
    }

    @Test
    public void testUpdate() throws ObjectDoesntExistException, WrongVersionException {
        // setup pre-existing Switch
        KVSwitch sw = new KVSwitch(DPID1, NAMESPACE);
        sw.setStatus(STATUS.ACTIVE);
        sw.forceCreate();
        assertNotEquals(DataStoreClient.getClient().getVersionNonexistant(), sw.getVersion());
        assertSwitchInDataStore(DPID1, STATUS.ACTIVE);


        sw1.read();
        assertNotEquals(DataStoreClient.getClient().getVersionNonexistant(), sw1.getVersion());

        sw1.setStatus(STATUS.INACTIVE);
        sw1.update();
        assertNotEquals(DataStoreClient.getClient().getVersionNonexistant(), sw1.getVersion());
        assertNotEquals(sw.getVersion(), sw1.getVersion());
        assertEquals(DPID1, sw1.getDpid());
        assertEquals(STATUS.INACTIVE, sw1.getStatus());
    }

    @Test
    public void testDelete() throws ObjectDoesntExistException, WrongVersionException {
        // setup pre-existing Switch
        KVSwitch sw = new KVSwitch(DPID1, NAMESPACE);
        sw.setStatus(STATUS.ACTIVE);
        sw.forceCreate();
        assertNotEquals(DataStoreClient.getClient().getVersionNonexistant(), sw.getVersion());
        assertSwitchInDataStore(DPID1, STATUS.ACTIVE);


        try {
            sw1.read();
        } catch (ObjectDoesntExistException e) {
            fail("Failed reading switch to delete");
        }
        assertNotEquals(DataStoreClient.getClient().getVersionNonexistant(), sw1.getVersion());
        sw1.delete();
        assertSwitchNotInDataStore(DPID1);
    }

    @Test
    public void testForceDelete() {
        // setup pre-existing Switch
        KVSwitch sw = new KVSwitch(DPID1, NAMESPACE);
        sw.setStatus(STATUS.ACTIVE);
        sw.forceCreate();
        assertNotEquals(DataStoreClient.getClient().getVersionNonexistant(), sw.getVersion());
        assertSwitchInDataStore(DPID1, STATUS.ACTIVE);


        sw1.forceDelete();
        assertNotEquals(DataStoreClient.getClient().getVersionNonexistant(), sw1.getVersion());
    }

}
