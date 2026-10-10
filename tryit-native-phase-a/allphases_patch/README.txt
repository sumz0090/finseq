TRYIT Native All Phases v1.0.0 — combined D, E, F, G and H update

INSTALL ON THE MAIN WINDOWS OMS SERVER PC
1. Extract this ZIP completely. Double-click UPDATE_TRYIT_ALL_PHASES.bat.
2. The updater detects the running OMS folder, verifies its exact version,
   backs up the three program files, installs the APIs, restarts that server
   with its current command/port, and checks health and protected endpoints.
   If detection is ambiguous, select the installed folder containing server.py.
   Keep the restarted server console open. Refresh existing browser clients.
3. Install TRYIT_Native_All_Phases_v1.0.0.apk over your Phase C Android app.
   Same app ID and permanent certificate; do not uninstall the existing app.
4. Unlock/sign in, then open All Modules from the Dashboard.

SUPPORTED SERVER BASES
- The exact unchanged Phase 10.37 server.py (10.38 if that file is identical).
- The exact previously delivered Native Phase C server and Orders API.
- This exact All Phases update, which is detected as already installed.
Locally modified or unknown program files are refused without replacement.
Existing Python 3 and Windows PowerShell are used; no new dependency install.
If a stopped server normally uses a custom port, start it normally before
updating so that its command and port can be preserved.

DATA AND RESTORE
Only server.py, mobile_orders_api.py and mobile_workspace_api.py are payloads.
Existing business data, user accounts, configuration, pages and backups are
preserved. Program-file backups are stored in update_safety_backups/NativeAll_*.
An installation or restart failure restores the previous program files and
attempts to restart the previous server automatically.
Double-click ROLLBACK_TRYIT_ALL_PHASES.bat to restore the latest program backup.
Rollback preserves business data saved after updating. It returns a Phase C
installation to Phase C, or removes both new APIs on an original installation.
Damaged backups and later edited program files are refused by rollback.

NATIVE MODULES
D: Nine master categories, searchable lists, profiles, guided create/edit,
   atomic rename across existing references and unused-record deletion.
E: Production/dispatch queues and order-stage actions; existing production
   batch history; sole/box stock, purchase orders, partial receiving, confirmed
   extra receipts, dated stock corrections and purchase-history metadata.
F: Party dashboard, order lifecycle ledger, period/status/search filters,
   grouped reports, drilldown and permission-controlled CSV export through
   the Android file picker.
G: User profiles and permissions, activation, WAN/IP/device restrictions,
   device approval/revocation, session controls and business/access/admin logs.
   Administrative screens require an administrator account; staff activity
   is limited to the server-authorized view.
H: Online-only confirmed saves, conflict/reset checks, idempotent retries,
   session-expiry sign-out, retained encrypted session/app-lock settings,
   draft restoration, responsive scrolling and protected screenshots/recents.

The existing server remains authoritative for data and permissions. Network
errors do not show a false save success or queue offline writes. Refresh and
review before retrying a change after a conflict. Native CSV reports contain
order lines; existing web PDF/printing workflows remain available on the web.
The party ledger reflects order lifecycle quantities, not accounting vouchers.

VERIFICATION
See VERIFICATION.json for build, test and signing results. Automated Android
tests use isolated fixtures, not your live server or customer data. Real phone
biometrics, your specific network, and installation on your live Windows PC
still require the normal device pilot. The update is not auto-deployed to it.
