# Security

## The repo must stay safe to make public at any moment

It is private today, but that is not the standard. Commit as if it opens tomorrow.

### 1. No personal data in tracked files

Home/office IP addresses, account numbers, phone numbers, real personal email addresses.

Operational values that happen to be personal go to Secret Manager as `AT_*` and are
fetched at deploy time. Hardcoded, every change to them becomes a commit, and those
commits together are a record of where someone lives.

### 2. No credentials, ever

`secrets/.env` is gitignored and has never been committed. Keep it that way.

### 3. Server public IPs do not belong in docs

**The site sits behind a GCP load balancer (2026-09-25, [023](../../docs/tasks/023-구글-부하분산기-전환.md)),
so this is live, not hypothetical.** `dig judozoo.com` returns the load balancer address — that one
is public by design and is not an access path to the VM. The VM's own address is: web ingress is
narrowed to Google's load balancer ranges (`130.211.0.0/22`, `35.191.0.0/16`, port 3000), so a direct
hit times out — but a firewall is one `terraform apply` away from being widened again. Don't write
the VM address down in the first place, so there is nothing to hunt for later.

The VM keeps a static external IP because Kiwoom allowlists it for **outbound** broker calls.

`terraform output vm_external_ips` has the value when it is actually needed.

Identifiers that are not themselves an access path — the GCP project id — are fine in docs.

### 4. Write weaknesses down

Recording a known weakness is encouraged. **The fix is to close the hole, not to delete
the note.**

That `default-allow-internal` leaves every port open inside the VPC belongs in writing.
[017](../../docs/tasks/017-공개-전환-구글-로그인.md) was caught out by `default-allow-ssh`
for exactly this reason — an unwritten trap gets walked into twice.

## Known exceptions

- **A KIS account number remains in git history** (a deleted migration SQL, 2026-05 ~ 08).
  The number alone cannot trade — app keys and secrets are absent from history — and
  rewriting history costs more than it buys. Re-check once before going public.
- These rules govern the **current HEAD**. Taking a value out of history means rewriting
  every hash with `git filter-repo`; keeping it out of the commit is the only cheap path.
