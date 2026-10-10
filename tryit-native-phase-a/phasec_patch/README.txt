TRYIT Native Phase C v0.3.0 - Native Orders

INSTALL (on the main Windows OMS server PC)
1. Extract this ZIP fully. Do not run files from inside the ZIP viewer.
2. Double-click APPLY_PHASE_C.bat.
   It detects the running KIRAN_OMS_App folder, checks the installed server
   fingerprint, creates a safety backup, replaces only server.py and adds
   mobile_orders_api.py, then restarts that server and checks the endpoint.
   If detection is ambiguous, select your installed folder containing server.py.
   Keep the new server console open. Refresh existing browser clients.
3. On Android, install TRYIT_Native_Pilot_PhaseC_v0.3.0.apk over Phase B v0.2.1.
   Same application ID and permanent signing certificate; no uninstall needed.
4. Unlock/login and open Orders from Dashboard.

COMPATIBILITY
The exact original Phase 10.37 server.py is supported. Phase 10.38 can use
this patch if its server.py is the same unchanged file. Different or locally
modified servers are refused; the updater does not guess or overwrite them.
State, data-path settings, user accounts, web pages and existing backups are
not replacement payloads. Only the two manifest files are changed.

The updater uses the existing Python 3 installation, standard-library modules
and Windows PowerShell process inspection; no new software install required.
It stops only the Python server identified as belonging to the selected folder.
If a process cannot be identified safely, close that server window and retry.
If a stopped server normally uses a custom port, start it normally first so
that the updater preserves its exact command/port. Do not change the app's
server.py or addon while the updater is working.

RESTORE SERVER FILES
Double-click ROLLBACK_PHASE_C.bat. It restores the latest timestamped backup
from KIRAN_OMS_App/update_safety_backups, removes the added module, and restarts.
Saved business orders remain; rollback is for program files only.
An install/restart failure restores the original program files automatically.
Later edited server files or damaged safety backups are refused by rollback.

NATIVE FEATURES
- Order list, search, status/master/date filters and 20/50/100 rows per page.
- Detail tabs: overview, order lines, production, dispatch and activity.
- Guided Party -> Product -> Sizes -> Delivery -> Review -> Save workflow;
  multiple product lines share one server-allocated group.
- Edit order fields, preserving group, print and existing stage metadata.
- Permission-checked Production, Ready, Dispatch, Hold/Resume and Cancel.
- Duplicate confirmation, direct-production confirmation, strict dates/sizes,
  idempotent create retry and conflict checks for stale edits/reset data.
- Same live web-server state, shoe-factory handoff and audit events.
- Existing app unlock/security and permanent signing retained; draft restored
  after app lock. Saves require an online server; no offline write queue.

VERIFICATION
Android Gradle build passed; APK v2/v3 signatures verified with the existing
Phase B permanent signing certificate. Functional API, actual HTTP and isolated
updater tests are included in the source repository. Windows updater process,
restart and rollback tests run on Windows CI. Physical Android phone UI testing
and installation on your live Windows server remain user-device checks.
