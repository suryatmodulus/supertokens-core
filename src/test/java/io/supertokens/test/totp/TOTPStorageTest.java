package io.supertokens.test.totp;

import io.supertokens.ProcessState;
import io.supertokens.cronjobs.deleteExpiredTotpTokens.DeleteExpiredTotpTokens;
import io.supertokens.featureflag.EE_FEATURES;
import io.supertokens.featureflag.FeatureFlagTestContent;
import io.supertokens.featureflag.exceptions.InvalidLicenseKeyException;
import io.supertokens.httpRequest.HttpResponseException;
import io.supertokens.pluginInterface.STORAGE_TYPE;
import io.supertokens.pluginInterface.exceptions.StorageQueryException;
import io.supertokens.pluginInterface.exceptions.StorageTransactionLogicException;
import io.supertokens.pluginInterface.totp.TOTPDevice;
import io.supertokens.pluginInterface.totp.TOTPStorage;
import io.supertokens.pluginInterface.totp.TOTPUsedCode;
import io.supertokens.pluginInterface.totp.exception.DeviceAlreadyExistsException;
import io.supertokens.pluginInterface.totp.exception.TotpNotEnabledException;
import io.supertokens.pluginInterface.totp.exception.UnknownDeviceException;
import io.supertokens.pluginInterface.totp.exception.UsedCodeAlreadyExistsException;
import io.supertokens.pluginInterface.totp.sqlStorage.TOTPSQLStorage;
import io.supertokens.storageLayer.StorageLayer;
import io.supertokens.test.TestingProcessManager;
import io.supertokens.test.Utils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;

import java.io.IOException;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

public class TOTPStorageTest {

    public class TestSetupResult {
        public TOTPSQLStorage storage;
        public TestingProcessManager.TestingProcess process;

        public TestSetupResult(TOTPSQLStorage storage, TestingProcessManager.TestingProcess process) {
            this.storage = storage;
            this.process = process;
        }
    }

    @Rule
    public TestRule watchman = Utils.getOnFailure();

    @AfterClass
    public static void afterTesting() {
        Utils.afterTesting();
    }

    @Before
    public void beforeEach() {
        Utils.reset();
    }

    public TestSetupResult initSteps()
            throws InterruptedException, StorageQueryException, InvalidLicenseKeyException, HttpResponseException,
            IOException {
        String[] args = {"../"};

        TestingProcessManager.TestingProcess process = TestingProcessManager.start(args);
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STARTED));

        if (StorageLayer.getStorage(process.getProcess()).getType() != STORAGE_TYPE.SQL) {
            return null;
        }
        TOTPSQLStorage storage = StorageLayer.getTOTPStorage(process.getProcess());

        FeatureFlagTestContent.getInstance(process.main)
                .setKeyValue(FeatureFlagTestContent.ENABLED_FEATURES, new EE_FEATURES[]{EE_FEATURES.TOTP});

        return new TestSetupResult(storage, process);
    }

    private static TOTPUsedCode[] getAllUsedCodesUtil(TOTPStorage storage, String userId)
            throws StorageQueryException, StorageTransactionLogicException {
        assert storage instanceof TOTPSQLStorage;
        TOTPSQLStorage sqlStorage = (TOTPSQLStorage) storage;

        return (TOTPUsedCode[]) sqlStorage.startTransaction(con -> {
            TOTPUsedCode[] usedCodes = sqlStorage.getAllUsedCodesDescOrder_Transaction(con, userId);
            sqlStorage.commitTransaction(con);
            return usedCodes;
        });
    }

    public static void insertUsedCodesUtil(TOTPSQLStorage storage, TOTPUsedCode[] usedCodes)
            throws StorageQueryException, StorageTransactionLogicException, TotpNotEnabledException,
            UsedCodeAlreadyExistsException {
        try {
            storage.startTransaction(con -> {
                try {
                    for (TOTPUsedCode usedCode : usedCodes) {
                        storage.insertUsedCode_Transaction(con, usedCode);
                    }
                } catch (TotpNotEnabledException | UsedCodeAlreadyExistsException e) {
                    throw new StorageTransactionLogicException(e);
                }
                storage.commitTransaction(con);

                return null;
            });
        } catch (StorageTransactionLogicException e) {
            Exception actual = e.actualException;
            if (actual instanceof TotpNotEnabledException) {
                throw (TotpNotEnabledException) actual;
            } else if (actual instanceof UsedCodeAlreadyExistsException) {
                throw (UsedCodeAlreadyExistsException) actual;
            }
            throw e;
        }
    }

    @Test
    public void createDeviceTests() throws Exception {
        TestSetupResult result = initSteps();
        if (result == null) {
            return;
        }
        TOTPSQLStorage storage = result.storage;

        TOTPDevice device1 = new TOTPDevice("user", "d1", "secret", 30, 1, false);
        TOTPDevice device2 = new TOTPDevice("user", "d2", "secret", 30, 1, true);
        TOTPDevice device2Duplicate = new TOTPDevice("user", "d2", "new-secret", 30, 1, false);

        storage.createDevice(device1);

        TOTPDevice[] storedDevices = storage.getDevices("user");
        assert (storedDevices.length == 1);
        assert storedDevices[0].equals(device1);

        storage.createDevice(device2);
        storedDevices = storage.getDevices("user");

        assert (storedDevices.length == 2);
        assert storedDevices[0].equals(device1);
        assert storedDevices[1].equals(device2);

        assertThrows(DeviceAlreadyExistsException.class, () -> storage.createDevice(device2Duplicate));

        result.process.kill();
        assertNotNull(result.process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STOPPED));
    }

    @Test
    public void verifyDeviceTests() throws Exception {
        TestSetupResult result = initSteps();
        if (result == null) {
            return;
        }
        TOTPSQLStorage storage = result.storage;

        TOTPDevice device = new TOTPDevice("user", "device", "secretKey", 30, 1, false);
        storage.createDevice(device);

        TOTPDevice[] storedDevices = storage.getDevices("user");
        assert (storedDevices.length == 1);
        assert (!storedDevices[0].verified);

        // Verify the device:
        storage.markDeviceAsVerified("user", "device");

        storedDevices = storage.getDevices("user");
        assert (storedDevices.length == 1);
        assert (storedDevices[0].verified);

        // Try to verify the device again:
        storage.markDeviceAsVerified("user", "device");

        // Try to verify a device that doesn't exist:
        assertThrows(UnknownDeviceException.class, () -> storage.markDeviceAsVerified("user", "non-existent-device"));
    }

    @Test
    public void getDevicesCount_TransactionTests() throws Exception {
        TestSetupResult result = initSteps();
        if (result == null) {
            return;
        }
        TOTPSQLStorage storage = result.storage;

        // Try to get the count for a user that doesn't exist (Should pass because
        // this is DB level txn that doesn't throw TotpNotEnabledException):
        int devicesCount = storage.startTransaction(con -> {
            TOTPDevice[] devices = storage.getDevices_Transaction(con, "non-existent-user");
            storage.commitTransaction(con);
            return devices.length;
        });
        assert devicesCount == 0;

        TOTPDevice device1 = new TOTPDevice("user", "device1", "sk1", 30, 1, false);
        TOTPDevice device2 = new TOTPDevice("user", "device2", "sk2", 30, 1, false);

        storage.createDevice(device1);
        storage.createDevice(device2);

        devicesCount = storage.startTransaction(con -> {
            TOTPDevice[] devices = storage.getDevices_Transaction(con, "user");
            storage.commitTransaction(con);
            return devices.length;
        });
        assert devicesCount == 2;
    }

    @Test
    public void removeUser_TransactionTests() throws Exception {
        TestSetupResult result = initSteps();
        if (result == null) {
            return;
        }
        TOTPSQLStorage storage = result.storage;

        // Try to remove a user that doesn't exist (Should pass because
        // this is DB level txn that doesn't throw TotpNotEnabledException):
        storage.startTransaction(con -> {
            storage.removeUser_Transaction(con, "non-existent-user");
            storage.commitTransaction(con);
            return null;
        });

        TOTPDevice device1 = new TOTPDevice("user", "device1", "sk1", 30, 1, false);
        TOTPDevice device2 = new TOTPDevice("user", "device2", "sk2", 30, 1, false);

        storage.createDevice(device1);
        storage.createDevice(device2);

        long now = System.currentTimeMillis();
        long expiryAfter10mins = now + 10 * 60 * 1000;

        TOTPUsedCode usedCode1 = new TOTPUsedCode("user", "code1", true, expiryAfter10mins, now);
        TOTPUsedCode usedCode2 = new TOTPUsedCode("user", "code2", false, expiryAfter10mins, now + 1);

        insertUsedCodesUtil(storage, new TOTPUsedCode[]{usedCode1, usedCode2});

        TOTPDevice[] storedDevices = storage.getDevices("user");
        assert (storedDevices.length == 2);

        TOTPUsedCode[] storedUsedCodes = getAllUsedCodesUtil(storage, "user");
        assert (storedUsedCodes.length == 2);

        storage.startTransaction(con -> {
            storage.removeUser_Transaction(con, "user");
            storage.commitTransaction(con);
            return null;
        });

        storedDevices = storage.getDevices("user");
        assert (storedDevices.length == 0);

        storedUsedCodes = getAllUsedCodesUtil(storage, "user");
        assert (storedUsedCodes.length == 0);
    }

    @Test
    public void deleteDevice_TransactionTests() throws Exception {
        TestSetupResult result = initSteps();
        if (result == null) {
            return;
        }
        TOTPSQLStorage storage = result.storage;

        TOTPDevice device1 = new TOTPDevice("user", "device1", "sk1", 30, 1, false);
        TOTPDevice device2 = new TOTPDevice("user", "device2", "sk2", 30, 1, false);

        storage.createDevice(device1);
        storage.createDevice(device2);

        TOTPDevice[] storedDevices = storage.getDevices("user");
        assert (storedDevices.length == 2);

        // Try to delete a device for a user that doesn't exist (Should pass because
        // this is DB level txn that doesn't throw TotpNotEnabledException):
        storage.startTransaction(con -> {
            storage.deleteDevice_Transaction(con, "non-existent-user", "device1");
            storage.commitTransaction(con);
            return null;
        });

        // Try to delete a device that doesn't exist:
        try {
            storage.startTransaction(con -> {
                storage.deleteDevice_Transaction(con, "user", "non-existent-device");
                storage.commitTransaction(con);
                return null;
            });
        } catch (Exception e) {
            assert (e instanceof UnknownDeviceException) ? true : false;
        }

        // Successfully delete device1:
        storage.startTransaction(con -> {
            storage.deleteDevice_Transaction(con, "user", "device1");
            storage.commitTransaction(con);
            return null;
        });

        storedDevices = storage.getDevices("user");
        assert (storedDevices.length == 1); // device2 should still be there
    }

    @Test
    public void updateDeviceNameTests() throws Exception {
        TestSetupResult result = initSteps();
        if (result == null) {
            return;
        }
        TOTPSQLStorage storage = result.storage;

        TOTPDevice device = new TOTPDevice("user", "device", "secretKey", 30, 1, false);
        storage.createDevice(device);

        TOTPDevice[] storedDevices = storage.getDevices("user");
        assert (storedDevices.length == 1);
        assert (storedDevices[0].deviceName.equals("device"));

        // Try to update a device that doesn't exist:
        assertThrows(UnknownDeviceException.class,
                () -> storage.updateDeviceName("user", "non-existent-device", "new-device-name"));

        // Update the device name:
        storage.updateDeviceName("user", "device", "updated-device-name");

        storedDevices = storage.getDevices("user");
        assert (storedDevices.length == 1);
        assert (storedDevices[0].deviceName.equals("updated-device-name"));

        // Try to create a new device and rename it to the same name as an existing
        // device:
        TOTPDevice newDevice = new TOTPDevice("user", "new-device", "secretKey", 30, 1, false);
        storage.createDevice(newDevice);

        assertThrows(DeviceAlreadyExistsException.class,
                () -> storage.updateDeviceName("user", "new-device", "updated-device-name"));

        // Try to rename the device the same name (Should work at database level):
        storage.updateDeviceName("user", "updated-device-name", "updated-device-name");
    }

    @Test
    public void getDevicesTest() throws Exception {
        TestSetupResult result = initSteps();
        if (result == null) {
            return;
        }
        TOTPSQLStorage storage = result.storage;

        TOTPDevice device1 = new TOTPDevice("user", "d1", "secretKey", 30, 1, false);
        TOTPDevice device2 = new TOTPDevice("user", "d2", "secretKey", 30, 1, false);

        storage.createDevice(device1);
        storage.createDevice(device2);

        TOTPDevice[] storedDevices = storage.getDevices("user");

        assert (storedDevices.length == 2);
        assert (storedDevices[0].deviceName.equals("d1"));
        assert (storedDevices[1].deviceName.equals("d2"));

        storedDevices = storage.getDevices("non-existent-user");
        assert (storedDevices.length == 0);
    }

    @Test
    public void insertUsedCodeTest() throws Exception {
        TestSetupResult result = initSteps();
        if (result == null) {
            return;
        }
        TOTPSQLStorage storage = result.storage;
        long nextDay = System.currentTimeMillis() + 1000 * 60 * 60 * 24; // 1 day from now
        long now = System.currentTimeMillis();

        // Insert a long lasting valid code and check that it's returned when queried:
        {
            TOTPDevice device = new TOTPDevice("user", "device", "secretKey", 30, 1, false);
            TOTPUsedCode code = new TOTPUsedCode("user", "1234", true, nextDay, now);

            storage.createDevice(device);
            insertUsedCodesUtil(storage, new TOTPUsedCode[]{code});
            TOTPUsedCode[] usedCodes = getAllUsedCodesUtil(storage, "user");

            assert (usedCodes.length == 1);
            assert usedCodes[0].equals(code);
        }

        // Try to insert a code with same user and created time. It should fail:
        {
            TOTPUsedCode codeWithRepeatedCreatedTime = new TOTPUsedCode("user", "any-code", true, nextDay, now);
            assertThrows(UsedCodeAlreadyExistsException.class,
                    () -> insertUsedCodesUtil(storage, new TOTPUsedCode[]{codeWithRepeatedCreatedTime}));
        }

        // Try to insert code when user doesn't have any device (i.e. TOTP not enabled)
        {
            assertThrows(TotpNotEnabledException.class,
                    () -> insertUsedCodesUtil(storage, new TOTPUsedCode[]{
                            new TOTPUsedCode("new-user-without-totp", "1234", true, nextDay,
                                    System.currentTimeMillis())
                    }));
        }

        // Try to insert code after user has atleast one device (i.e. TOTP enabled)
        {
            TOTPDevice newDevice = new TOTPDevice("user", "new-device", "secretKey", 30, 1, false);
            storage.createDevice(newDevice);
            insertUsedCodesUtil(
                    storage,
                    new TOTPUsedCode[]{
                            new TOTPUsedCode("user", "1234", true, nextDay, System.currentTimeMillis())
                    });
        }

        // Try to insert code when user doesn't exist:
        assertThrows(TotpNotEnabledException.class,
                () -> insertUsedCodesUtil(storage, new TOTPUsedCode[]{
                        new TOTPUsedCode("non-existent-user", "1234", true, nextDay,
                                System.currentTimeMillis())
                }));
    }

    @Test
    public void getAllUsedCodesTest() throws Exception {
        TestSetupResult result = initSteps();
        if (result == null) {
            return;
        }
        TOTPSQLStorage storage = result.storage;

        TOTPUsedCode[] usedCodes = getAllUsedCodesUtil(storage, "non-existent-user");
        assert (usedCodes.length == 0);

        long now = System.currentTimeMillis();
        long nextDay = now + 1000 * 60 * 60 * 24; // 1 day from now
        long prevDay = now - 1000 * 60 * 60 * 24; // 1 day ago

        TOTPDevice device = new TOTPDevice("user", "device", "secretKey", 30, 1, false);
        TOTPUsedCode validCode1 = new TOTPUsedCode("user", "valid1", true, nextDay, now + 1);
        TOTPUsedCode invalidCode = new TOTPUsedCode("user", "invalid", false, nextDay, now + 2);
        TOTPUsedCode expiredCode = new TOTPUsedCode("user", "expired", true, prevDay, now + 3);
        TOTPUsedCode expiredInvalidCode = new TOTPUsedCode("user", "ex-in", false, prevDay, now + 4);
        TOTPUsedCode validCode2 = new TOTPUsedCode("user", "valid2", true, nextDay, now + 5);
        TOTPUsedCode validCode3 = new TOTPUsedCode("user", "valid3", true, nextDay, now + 6);

        storage.createDevice(device);
        insertUsedCodesUtil(storage, new TOTPUsedCode[]{
                validCode1, invalidCode,
                expiredCode, expiredInvalidCode,
                validCode2, validCode3
        });

        // Try to create a code with same user and created time. It should fail:
        assertThrows(UsedCodeAlreadyExistsException.class,
                () -> insertUsedCodesUtil(storage, new TOTPUsedCode[]{
                        new TOTPUsedCode("user", "any-code", true, nextDay, now + 1)
                }));

        usedCodes = getAllUsedCodesUtil(storage, "user");
        assert (usedCodes.length == 6);

        DeleteExpiredTotpTokens.getInstance(result.process.getProcess()).run();

        usedCodes = getAllUsedCodesUtil(storage, "user");
        assert (usedCodes.length == 4); // expired codes shouldn't be returned
        assert (usedCodes[0].equals(validCode3)); // order is DESC by created time (now + X)
        assert (usedCodes[1].equals(validCode2));
        assert (usedCodes[2].equals(invalidCode));
        assert (usedCodes[3].equals(validCode1));
    }

    @Test
    public void removeExpiredCodesTest() throws Exception {
        TestSetupResult result = initSteps();
        if (result == null) {
            return;
        }
        TOTPSQLStorage storage = result.storage;

        long now = System.currentTimeMillis();
        long nextDay = System.currentTimeMillis() + 1000 * 60 * 60 * 24; // 1 day from now
        long halfSecond = System.currentTimeMillis() + 500; // 500ms from now

        TOTPDevice device = new TOTPDevice("user", "device", "secretKey", 30, 1, false);
        TOTPUsedCode validCodeToLive = new TOTPUsedCode("user", "valid", true, nextDay, now);
        TOTPUsedCode invalidCodeToLive = new TOTPUsedCode("user", "invalid", false, nextDay, now + 1);
        TOTPUsedCode validCodeToExpire = new TOTPUsedCode("user", "valid", true, halfSecond, now + 2);
        TOTPUsedCode invalidCodeToExpire = new TOTPUsedCode("user", "invalid", false, halfSecond, now + 3);

        storage.createDevice(device);
        insertUsedCodesUtil(storage, new TOTPUsedCode[]{
                validCodeToLive, invalidCodeToLive,
                validCodeToExpire, invalidCodeToExpire
        });

        TOTPUsedCode[] usedCodes = getAllUsedCodesUtil(storage, "user");
        assert (usedCodes.length == 4);

        // After 500ms seconds pass:
        Thread.sleep(500);

        storage.removeExpiredCodes(System.currentTimeMillis());

        usedCodes = getAllUsedCodesUtil(storage, "user");
        assert (usedCodes.length == 2);
        assert (usedCodes[0].equals(invalidCodeToLive));
        assert (usedCodes[1].equals(validCodeToLive));
    }
}