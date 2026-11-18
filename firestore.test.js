const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { describe, it, before, after, beforeEach } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");

const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const DATABASE_ID = "ai-studio-android-remixnex-09ac45d4-6e10-4d97-a667-298eae4d399d";
const ADMIN_UID = "admin-uid-1";
const ADMIN_EMAIL = "sarfarazmech00@gmail.com";
const USER_A_UID = "user-a-uid";
const USER_B_UID = "user-b-uid";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

let testEnv;

function getDb(context) {
  return context.firestore({ databaseId: DATABASE_ID });
}

function recentDate(offsetMs = 1000) {
  return new Date(Date.now() - offsetMs);
}

function futureDate(offsetMs) {
  return new Date(Date.now() + offsetMs);
}

before(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules: fs.readFileSync("./firestore.rules", "utf8"),
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

describe("NEXUS TRADING AI Firestore Security Rules", () => {
  it("denies all unauthenticated reads and writes", async () => {
    const anonDb = getDb(testEnv.unauthenticatedContext());
    await assertFails(anonDb.collection("app_config").doc("support").get());
    await assertFails(anonDb.collection("users").doc(USER_A_UID).get());
    await assertFails(anonDb.collection("activation_codes").get());
    await assertFails(
      anonDb.collection("users").doc(USER_A_UID).set({
        userId: USER_A_UID,
        email: "a@example.com",
      })
    );
  });

  it("denies admin access when email is not verified or belongs to normal user", async () => {
    const spoofDb = getDb(
      testEnv.authenticatedContext("spoof-uid", {
        email: ADMIN_EMAIL,
        email_verified: false,
      })
    );
    const normalDb = getDb(
      testEnv.authenticatedContext(USER_A_UID, {
        email: "usera@gmail.com",
        email_verified: true,
      })
    );

    const now = recentDate();
    await assertFails(
      spoofDb.collection("app_config").doc("support").set({
        whatsappNumber: "+923001234567",
        supportMessage: "Hi",
        updatedByUid: "spoof-uid",
        updatedAt: now,
      })
    );
    await assertFails(normalDb.collection("activation_codes").get());
    await assertFails(normalDb.collection("users").get());
    await assertFails(normalDb.collection("admins").doc(ADMIN_UID).get());
  });

  it("allows verified admin to manage WhatsApp support config and rejects invalid formats", async () => {
    const adminDb = getDb(
      testEnv.authenticatedContext(ADMIN_UID, {
        email: ADMIN_EMAIL,
        email_verified: true,
      })
    );
    const userDb = getDb(
      testEnv.authenticatedContext(USER_A_UID, {
        email: "usera@gmail.com",
        email_verified: true,
      })
    );

    const now = recentDate();
    await assertSucceeds(
      adminDb.collection("app_config").doc("support").set({
        whatsappNumber: "+923001234567",
        supportMessage: "Contact admin for activation code",
        updatedByUid: ADMIN_UID,
        updatedAt: now,
      })
    );

    const supportSnap = await assertSucceeds(userDb.collection("app_config").doc("support").get());
    assert.equal(supportSnap.data().whatsappNumber, "+923001234567");

    // Empty whatsappNumber is allowed (unconfigured state)
    await assertSucceeds(
      adminDb.collection("app_config").doc("support").set({
        whatsappNumber: "",
        supportMessage: "",
        updatedByUid: ADMIN_UID,
        updatedAt: now,
      })
    );

    await assertFails(
      adminDb.collection("app_config").doc("support").set({
        whatsappNumber: "not-a-phone-number!",
        updatedByUid: ADMIN_UID,
        updatedAt: now,
      })
    );
  });

  it("enforces ONLY two subscription plans (MONTHLY PKR 500, GOLDEN ANNUAL PKR 4000)", async () => {
    const adminDb = getDb(
      testEnv.authenticatedContext(ADMIN_UID, {
        email: ADMIN_EMAIL,
        email_verified: true,
      })
    );
    const now = recentDate();

    await assertSucceeds(
      adminDb.collection("activation_codes").doc("NX-MON-ABCD1234").set({
        code: "NX-MON-ABCD1234",
        planId: "MONTHLY",
        planName: "MONTHLY",
        pricePkr: 500,
        durationMonths: 1,
        durationDays: 30,
        status: "UNUSED",
        note: "Test monthly",
        createdByUid: ADMIN_UID,
        createdAt: now,
        updatedAt: now,
      })
    );

    await assertSucceeds(
      adminDb.collection("activation_codes").doc("NX-GLD-WXYZ5678").set({
        code: "NX-GLD-WXYZ5678",
        planId: "GOLDEN_ANNUAL",
        planName: "GOLDEN ANNUAL",
        pricePkr: 4000,
        durationMonths: 12,
        durationDays: 365,
        status: "UNUSED",
        createdByUid: ADMIN_UID,
        createdAt: now,
        updatedAt: now,
      })
    );

    // Reject an invented 3rd plan or mismatched price
    await assertFails(
      adminDb.collection("activation_codes").doc("NX-BAD-11112222").set({
        code: "NX-BAD-11112222",
        planId: "WEEKLY",
        planName: "WEEKLY",
        pricePkr: 200,
        durationMonths: 0,
        durationDays: 7,
        status: "UNUSED",
        createdByUid: ADMIN_UID,
        createdAt: now,
        updatedAt: now,
      })
    );
    await assertFails(
      adminDb.collection("activation_codes").doc("NX-BAD-33334444").set({
        code: "NX-BAD-33334444",
        planId: "MONTHLY",
        planName: "MONTHLY",
        pricePkr: 999,
        durationMonths: 1,
        durationDays: 30,
        status: "UNUSED",
        createdByUid: ADMIN_UID,
        createdAt: now,
        updatedAt: now,
      })
    );
  });

  it("blocks user privilege escalation, standalone activation forgery, and future timestamp spoofing", async () => {
    const userADb = getDb(
      testEnv.authenticatedContext(USER_A_UID, {
        email: "usera@gmail.com",
        email_verified: true,
      })
    );
    const now = recentDate();

    // Cannot create user doc as ADMIN or ACTIVE
    await assertFails(
      userADb.collection("users").doc(USER_A_UID).set({
        userId: USER_A_UID,
        email: "usera@gmail.com",
        displayName: "User A",
        role: "ADMIN",
        subscriptionStatus: "NONE",
        accountDisabled: false,
        lastSeenAt: now,
        createdAt: now,
        updatedAt: now,
      })
    );
    await assertFails(
      userADb.collection("users").doc(USER_A_UID).set({
        userId: USER_A_UID,
        email: "usera@gmail.com",
        displayName: "User A",
        role: "USER",
        subscriptionStatus: "ACTIVE",
        accountDisabled: false,
        lastSeenAt: now,
        createdAt: now,
        updatedAt: now,
      })
    );

    // Valid initial profile creation
    await assertSucceeds(
      userADb.collection("users").doc(USER_A_UID).set({
        userId: USER_A_UID,
        email: "usera@gmail.com",
        displayName: "User A",
        role: "USER",
        subscriptionStatus: "NONE",
        accountDisabled: false,
        lastSeenAt: now,
        createdAt: now,
        updatedAt: now,
      })
    );

    // Reject cross-user write
    await assertFails(
      userADb.collection("users").doc(USER_B_UID).set({
        userId: USER_B_UID,
        email: "userb@gmail.com",
        displayName: "User B",
        role: "USER",
        subscriptionStatus: "NONE",
        accountDisabled: false,
        lastSeenAt: now,
        createdAt: now,
        updatedAt: now,
      })
    );

    // Reject standalone self-activation without atomic batch redeeming UNUSED code
    await assertFails(
      userADb.collection("users").doc(USER_A_UID).update({
        subscriptionStatus: "ACTIVE",
        planId: "GOLDEN_ANNUAL",
        planName: "GOLDEN ANNUAL",
        pricePkr: 4000,
        activeCode: "FAKE-CODE-1234",
        activatedAt: now,
        expiresAt: futureDate(365 * 24 * 60 * 60 * 1000),
        lastSeenAt: now,
        updatedAt: now,
      })
    );

    // Reject tampered future lastSeenAt
    await assertFails(
      userADb.collection("users").doc(USER_A_UID).update({
        lastSeenAt: futureDate(60 * 1000),
        updatedAt: now,
      })
    );
  });

  it("allows atomic single-use activation and blocks duplicate or disabled reuse", async () => {
    const adminDb = getDb(
      testEnv.authenticatedContext(ADMIN_UID, {
        email: ADMIN_EMAIL,
        email_verified: true,
      })
    );
    const userADb = getDb(
      testEnv.authenticatedContext(USER_A_UID, {
        email: "usera@gmail.com",
        email_verified: true,
      })
    );
    const userBDb = getDb(
      testEnv.authenticatedContext(USER_B_UID, {
        email: "userb@gmail.com",
        email_verified: true,
      })
    );

    const now = recentDate();
    const codeId = "NX-MON-99887766";
    await assertSucceeds(
      adminDb.collection("activation_codes").doc(codeId).set({
        code: codeId,
        planId: "MONTHLY",
        planName: "MONTHLY",
        pricePkr: 500,
        durationMonths: 1,
        durationDays: 30,
        status: "UNUSED",
        createdByUid: ADMIN_UID,
        createdAt: now,
        updatedAt: now,
      })
    );

    await assertSucceeds(
      userADb.collection("users").doc(USER_A_UID).set({
        userId: USER_A_UID,
        email: "usera@gmail.com",
        displayName: "User A",
        role: "USER",
        subscriptionStatus: "NONE",
        accountDisabled: false,
        lastSeenAt: now,
        createdAt: now,
        updatedAt: now,
      })
    );
    await assertSucceeds(
      userBDb.collection("users").doc(USER_B_UID).set({
        userId: USER_B_UID,
        email: "userb@gmail.com",
        displayName: "User B",
        role: "USER",
        subscriptionStatus: "NONE",
        accountDisabled: false,
        lastSeenAt: now,
        createdAt: now,
        updatedAt: now,
      })
    );

    // Tampered 10-year expiry on MONTHLY code must fail
    const badBatch = userADb.batch();
    const tamperedExpiry = futureDate(3650 * 24 * 60 * 60 * 1000);
    badBatch.update(userADb.collection("activation_codes").doc(codeId), {
      status: "ACTIVATED",
      activatedByUid: USER_A_UID,
      activatedByEmail: "usera@gmail.com",
      activatedAt: now,
      expiresAt: tamperedExpiry,
      updatedAt: now,
    });
    badBatch.update(userADb.collection("users").doc(USER_A_UID), {
      subscriptionStatus: "ACTIVE",
      planId: "MONTHLY",
      planName: "MONTHLY",
      pricePkr: 500,
      activeCode: codeId,
      activatedAt: now,
      expiresAt: tamperedExpiry,
      lastSeenAt: now,
      updatedAt: now,
    });
    await assertFails(badBatch.commit());

    // Valid atomic activation for 30 days succeeds
    const expiry30Days = futureDate(30 * 24 * 60 * 60 * 1000);
    const goodBatch = userADb.batch();
    goodBatch.update(userADb.collection("activation_codes").doc(codeId), {
      status: "ACTIVATED",
      activatedByUid: USER_A_UID,
      activatedByEmail: "usera@gmail.com",
      activatedAt: now,
      expiresAt: expiry30Days,
      updatedAt: now,
    });
    goodBatch.update(userADb.collection("users").doc(USER_A_UID), {
      subscriptionStatus: "ACTIVE",
      planId: "MONTHLY",
      planName: "MONTHLY",
      pricePkr: 500,
      activeCode: codeId,
      activatedAt: now,
      expiresAt: expiry30Days,
      lastSeenAt: now,
      updatedAt: now,
    });
    await assertSucceeds(goodBatch.commit());

    // Duplicate redemption by User B must fail
    const dupBatch = userBDb.batch();
    dupBatch.update(userBDb.collection("activation_codes").doc(codeId), {
      status: "ACTIVATED",
      activatedByUid: USER_B_UID,
      activatedByEmail: "userb@gmail.com",
      activatedAt: now,
      expiresAt: expiry30Days,
      updatedAt: now,
    });
    dupBatch.update(userBDb.collection("users").doc(USER_B_UID), {
      subscriptionStatus: "ACTIVE",
      planId: "MONTHLY",
      planName: "MONTHLY",
      pricePkr: 500,
      activeCode: codeId,
      activatedAt: now,
      expiresAt: expiry30Days,
      lastSeenAt: now,
      updatedAt: now,
    });
    await assertFails(dupBatch.commit());

    // Admin revokes user and disables account + revokes code
    await assertSucceeds(
      adminDb.collection("users").doc(USER_A_UID).update({
        subscriptionStatus: "REVOKED",
        accountDisabled: true,
        updatedAt: now,
      })
    );
    await assertSucceeds(
      adminDb.collection("activation_codes").doc(codeId).update({
        status: "REVOKED",
        updatedAt: now,
      })
    );

    // Disabled user cannot redeem a fresh UNUSED code
    const newCode = "NX-GLD-55443322";
    await assertSucceeds(
      adminDb.collection("activation_codes").doc(newCode).set({
        code: newCode,
        planId: "GOLDEN_ANNUAL",
        planName: "GOLDEN ANNUAL",
        pricePkr: 4000,
        durationMonths: 12,
        durationDays: 365,
        status: "UNUSED",
        createdByUid: ADMIN_UID,
        createdAt: now,
        updatedAt: now,
      })
    );
    const expiry365Days = futureDate(365 * 24 * 60 * 60 * 1000);
    const blockedBatch = userADb.batch();
    blockedBatch.update(userADb.collection("activation_codes").doc(newCode), {
      status: "ACTIVATED",
      activatedByUid: USER_A_UID,
      activatedByEmail: "usera@gmail.com",
      activatedAt: now,
      expiresAt: expiry365Days,
      updatedAt: now,
    });
    blockedBatch.update(userADb.collection("users").doc(USER_A_UID), {
      subscriptionStatus: "ACTIVE",
      planId: "GOLDEN_ANNUAL",
      planName: "GOLDEN ANNUAL",
      pricePkr: 4000,
      activeCode: newCode,
      activatedAt: now,
      expiresAt: expiry365Days,
      lastSeenAt: now,
      updatedAt: now,
    });
    await assertFails(blockedBatch.commit());
  });

  it("allows owner heartbeat and auto-expiry transition when past expiresAt", async () => {
    const adminDb = getDb(
      testEnv.authenticatedContext(ADMIN_UID, {
        email: ADMIN_EMAIL,
        email_verified: true,
      })
    );
    const userADb = getDb(
      testEnv.authenticatedContext(USER_A_UID, {
        email: "usera@gmail.com",
        email_verified: true,
      })
    );

    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      const rootDb = getDb(ctx);
      await rootDb.collection("users").doc(USER_A_UID).set({
        userId: USER_A_UID,
        email: "usera@gmail.com",
        displayName: "User A",
        role: "USER",
        subscriptionStatus: "ACTIVE",
        accountDisabled: false,
        planId: "MONTHLY",
        planName: "MONTHLY",
        pricePkr: 500,
        activeCode: "NX-MON-PAST1234",
        activatedAt: recentDate(35 * 24 * 60 * 60 * 1000),
        expiresAt: recentDate(5 * 24 * 60 * 60 * 1000),
        lastSeenAt: recentDate(1000),
        createdAt: recentDate(35 * 24 * 60 * 60 * 1000),
        updatedAt: recentDate(1000),
      });
    });

    const now = recentDate();
    await assertSucceeds(
      userADb.collection("users").doc(USER_A_UID).update({
        lastSeenAt: now,
        updatedAt: now,
      })
    );

    await assertSucceeds(
      userADb.collection("users").doc(USER_A_UID).update({
        subscriptionStatus: "EXPIRED",
        lastSeenAt: now,
        updatedAt: now,
      })
    );

    const adminRead = await assertSucceeds(adminDb.collection("users").doc(USER_A_UID).get());
    assert.equal(adminRead.data().subscriptionStatus, "EXPIRED");
  });

  it("enforces admin-only code/user revocation and strict createdAt == updatedAt on code creation", async () => {
    const adminDb = getDb(
      testEnv.authenticatedContext(ADMIN_UID, {
        email: ADMIN_EMAIL,
        email_verified: true,
      })
    );
    const userADb = getDb(
      testEnv.authenticatedContext(USER_A_UID, {
        email: "usera@gmail.com",
        email_verified: true,
      })
    );

    const t1 = recentDate(2000);
    const t2 = recentDate(1000);

    // Mismatched createdAt vs updatedAt on admin code creation must fail
    await assertFails(
      adminDb.collection("activation_codes").doc("NXM-BAD-TIME1234").set({
        code: "NXM-BAD-TIME1234",
        planId: "MONTHLY",
        planName: "MONTHLY",
        pricePkr: 500,
        durationMonths: 1,
        durationDays: 30,
        status: "UNUSED",
        createdByUid: ADMIN_UID,
        createdAt: t1,
        updatedAt: t2,
      })
    );

    // Identical createdAt == updatedAt succeeds
    await assertSucceeds(
      adminDb.collection("activation_codes").doc("NXM-GOOD-1234567").set({
        code: "NXM-GOOD-1234567",
        planId: "MONTHLY",
        planName: "MONTHLY",
        pricePkr: 500,
        durationMonths: 1,
        durationDays: 30,
        status: "UNUSED",
        createdByUid: ADMIN_UID,
        createdAt: t1,
        updatedAt: t1,
      })
    );

    // Normal user cannot revoke code or disable account
    await assertFails(
      userADb.collection("activation_codes").doc("NXM-GOOD-1234567").update({
        status: "REVOKED",
        updatedAt: t2,
      })
    );
    await assertFails(
      userADb.collection("users").doc(USER_A_UID).update({
        accountDisabled: true,
        subscriptionStatus: "REVOKED",
        updatedAt: t2,
      })
    );
  });
});
