# WorkflowGuard controlled target lab

This lab provides realistic web and API workflows without testing third-party
production services.

## Targets

| Target | Local origin | Coverage |
|---|---|---|
| OWASP Juice Shop 19.2.1 | `http://127.0.0.1:3000` | Login, cart, coupons, checkout, orders, account state |
| OWASP crAPI 1.1.6 | `http://127.0.0.1:8888` | Registration, REST APIs, object ownership, orders, multi-step flows |
| crAPI MailHog | `http://127.0.0.1:18025` | Manual retrieval of local test email only; not a test target |

All published ports bind to loopback. The targets are intentionally vulnerable
and must not be exposed to a LAN or the public internet.

## Commands

Start or resume the lab:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File scripts/lab/Start-WorkflowGuardLab.ps1
```

Verify the local endpoints:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File scripts/lab/Test-WorkflowGuardLab.ps1
```

Start Burp with the development build of WorkflowGuard in a dedicated shell:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File scripts/run-burp-dev.ps1 `
  -ProjectConfigFile lab/burp-project-options.json
```

The supplied project configuration creates the dedicated listener on
`127.0.0.1:8080` with interception disabled.

After Burp finishes initializing and its loopback listener is active, send the
read-only guest baseline from another shell:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File scripts/lab/Send-GuestBaselineThroughBurp.ps1
```

The baseline sends one sequential `GET` to each approved target, records no
response body, and writes a sanitized receipt under
`agy/output/workflowguard-local-owasp-lab-2026/`. It proves only proxy transport
and target availability; it is not a vulnerability assessment.

Validate all four pre-provisioned accounts for both targets:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File scripts/lab/Test-WorkflowGuardLabAccountsThroughBurp.ps1 `
  -JuiceCredentialFile "C:\path\to\OWASP Juice Shop accounts.txt" `
  -CrapiCredentialFile "C:\path\to\crAPI accounts.txt"
```

Run the read-only actor-isolation campaign for two independent actor pairs:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File scripts/lab/Test-WorkflowGuardActorIsolationThroughBurp.ps1 `
  -JuiceCredentialFile "C:\path\to\OWASP Juice Shop accounts.txt" `
  -CrapiCredentialFile "C:\path\to\crAPI accounts.txt" `
  -AccountLabels A,B

powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File scripts/lab/Test-WorkflowGuardActorIsolationThroughBurp.ps1 `
  -JuiceCredentialFile "C:\path\to\OWASP Juice Shop accounts.txt" `
  -CrapiCredentialFile "C:\path\to\crAPI accounts.txt" `
  -AccountLabels C,D
```

Finally, reproduce the Juice Shop authorization result through WorkflowGuard's
production execution coordinator:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File scripts/lab/Test-WorkflowGuardRealLabAuthorizationReplay.ps1 `
  -JuiceCredentialFile "C:\path\to\OWASP Juice Shop accounts.txt"
```

All three scripts parse credentials into process memory only. Their retained
receipts contain labels and boolean outcomes, never credentials, authorization
values, response bodies, or object identifiers. They also force proxy use for
loopback destinations: the standard .NET proxy bypasses `127.0.0.1`, so merely
finding the Burp process that owns a port is not valid transport evidence.

Stop containers while preserving their current state:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File scripts/lab/Stop-WorkflowGuardLab.ps1
```

Reset all disposable lab state:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File scripts/lab/Reset-WorkflowGuardLab.ps1 -ConfirmReset
```

The start script downloads the official crAPI `v1.1.6` source archive into the
ignored `.workflowguard-lab/` directory and verifies its pinned SHA-256 before
running the official Docker Compose definition. The upstream `v1.1.6` tag still
contains `VERSION=1.1.5`; the script verifies this known metadata mismatch
explicitly instead of silently accepting an arbitrary version.

`lab/crapi.override.yml` restricts the crAPI web service to a single loopback
port and maps MailHog to `18025`, avoiding the existing local service on `8025`.
All container images are pinned by digest in `lab/images.lock.json`; a future
upgrade therefore requires an explicit lock update and a fresh verification.

## Identities and secrets

Use `lab/identities.example.json` only as a reference for non-secret identity
labels. Passwords, session cookies, tokens, and authorization headers belong in
the Burp project or a local password manager. Do not paste them into AGY, Codex,
Git, workflow archives, or reports.

The crAPI test addresses use `example.com`, whose mail is delivered to the local
MailHog service by the official deployment. Juice Shop test addresses use the
reserved `.test` domain. Neither requires a third-party CAPTCHA service.

## Safety

- Keep Docker port mappings on `127.0.0.1`.
- Keep WorkflowGuard concurrency at `1`.
- Do not include MailHog in the WorkflowGuard target scope.
- Do not run denial-of-service, resource exhaustion, external SSRF, or
  password-bruteforce cases.
- Use two disposable users per target for authorization-difference tests.
- Reset the lab after state-changing test campaigns.
