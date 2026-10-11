# TRYIT OMS Premium v1.1.0

Install the server update first, then the signed Premium APK over the existing TRYIT app. Do not uninstall the old app.

## Server update (Windows OMS PC)

1. Extract this ZIP on the OMS server PC.
2. Double-click UPDATE_TRYIT_ALL_PHASES.bat.
3. The updater identifies the running OMS folder and exact supported version, saves a safety backup, stops only that server, installs the four API files, restarts it with the same command, and verifies health and protected endpoints.
4. If multiple servers are found, choose the intended folder with the updater's --app-folder option. Unknown/custom server versions are refused without overwriting files.

Supported bases: Phase 10.37/10.38, Native Phase C, and Native All Phases v1.0.0. Existing web UI, users, business data, configuration and backups are preserved. No database reset or reimport is needed.

Use ROLLBACK_TRYIT_ALL_PHASES.bat to restore the selected safety backup. The updater does not overwrite later custom edits during rollback.

## Android update

Install TRYIT_OMS_Premium_v1.1.0.apk over the previous app. It keeps the same application ID and permanent signing certificate. Version code increases to 6.

The release app connects internally to https://oms.tryitexclusive.com using HTTPS. There is no server URL field. On first use, sign in with the existing OMS account, then choose Screen Lock, App PIN, or Skip.

Screen Lock supports strong biometrics/device credentials on Android 11+, and device credentials on Android 8–10. Supported biometric options depend on the phone. App PIN accepts 4–6 digits; five wrong attempts pause further attempts for 30 seconds.

With App Lock enabled, successful unlock renews the OMS session using a separate remembered-device credential valid for up to 90 days from enrollment. Passwords are not saved. Screen Lock protects the credential with an authentication-bound Android Keystore key; App PIN uses the existing encrypted Keystore store and verified PIN gate. PIN verification uses PBKDF2; existing PINs migrate after a correct unlock.

Normal session expiry does not require entering the password again. Admin force logout, device revoke, password/access changes, disable/delete, or credential expiry require fresh login. Server network/device restrictions still apply. Network failure leaves the app locked with an error so you can unlock/retry; it does not silently resubmit business writes. Unsaved business drafts survive background lock.

Skipping/disabling App Lock disables remembered-device renewal. Logging out clears the local credential and asks the server to revoke it. Internet access and the server patch are required for renewal. This release changes a previously saved server address to the fixed OMS host; users previously using another host must sign in on this host once.

## Design

Branded navy/white/red welcome screen, business overview, richer metric cards, consistent rounded forms and navigation: Dashboard, Orders, Operations, Masters, More. More opens the permission-aware module list including Dispatch, Stock & Purchases, Party Dashboard & Ledger, Reports, Administration and Activity & Audit.

## Verification

See VERIFICATION.json for the tested source/build and checks. Automated tests use isolated fixtures only. Physical fingerprint/face enrollment and the live OMS domain still need an on-device acceptance check; no live account data was used for testing.
