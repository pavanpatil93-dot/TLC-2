# Batch 05B review — 2026-11-25

Base: f931adfb40a08bba44ccb539b3332e2a0d5b664c. Isolated branch only. Preview branch diverges 46 ahead / 68 behind; do not merge blindly.

Live role metadata confirms tlc_web_runtime LOGIN, NOSUPERUSER, NOBYPASSRLS, NOCREATEROLE, NOCREATEDB. Direct runtime authentication remains untested. Existing 006 source and live project/property policies already require active employee and admin for cross-department access; Gemini's reported owner bypass is not present in those policies. No database changes applied.

Implemented application-level deny-by-default for non-employee account kinds in c9f3702b492803c9440eba070eb5e2ee3fb49b7d. Build pending.

Hold supplied 007 migration: recursive accounts/account_roles RLS policies risk infinite recursion; new super_admin role conflicts with existing application role model; submission read policy grants managers unscoped access; audit insert can be forged by active accounts. Hold employee route until migration and transition rules are validated. Hold runtime script: vacuous positive assertions, missing fixture provisioning, broad mutation probes, no positive audit insert and unsafe error logging. Hold CI secret-bearing job until protected environment and fixture configuration verified. Verification SQL is diagnostic, not an assertion suite. Do not enable private endpoints or apply migrations yet.

Next: review safe nonrecursive employee administration design, exact synthetic fixtures, and secret-free CI. Runtime tests require restricted credentials via approved protected environment. No production, main, Sheets or Vercel changes.
