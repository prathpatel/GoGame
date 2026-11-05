# Hosting GoGame on AWS

The simplest setup that works well for a Go server of any modest size: **one server**
(a Lightsail instance or an EC2 instance) runs everything: nginx, lila, lila-ws, KataGo, MongoDB and Redis. Around it, AWS provides
the address (Elastic IP), email (SES) and off-server backups (S3). DNS is at Cloudflare (for
a domain bought there) or Route 53.

```
players ──https──> Elastic IP ──> EC2 (Ubuntu 24.04)
                                   ├─ nginx :443 ── lila :9663 ── MongoDB, Redis, KataGo
                                   └─ nginx :443 ── lila-ws :9664
                    SES (email) <──┘   └──> S3 (nightly database backups)
```

`bin/install-server` does everything on the instance. The steps below are what you do in
the AWS console, in order. Allow about an hour, plus up to a day for SES approval.

## 1. AWS account

1. Create the account at <https://aws.amazon.com>. Turn on **MFA** for the root user, then do
   your daily work with an IAM user or IAM Identity Center user that has administrator access.
2. **Billing → Budgets → Create budget**, e.g. a monthly cost budget with an email alert, so
   nothing surprises you.
3. Pick a **region** close to your players (top right of the console), and use it for
   everything below.

## 2. The server

Two ways to get the same Ubuntu server. **Lightsail** is cheaper and simpler, with a fixed
monthly price; **EC2** gives more instance choices and IAM roles. `bin/install-server` works
on both.

### Lightsail (recommended for a small budget)

**Lightsail → Create instance**:

| Setting | Value |
| --- | --- |
| Platform, blueprint | **Linux/Unix**, **OS Only → Ubuntu 24.04 LTS** |
| Plan | **General purpose, 16 GB** ($84/month: 4 vCPUs, 320 GB SSD, 6 TB transfer). Lightsail's general purpose plans are burstable: a CPU kept busy for long, by many games against KataGo, gets slowed down. If that happens, consider the **compute optimized** plan at the same price (4 vCPUs, 8 GB), meant for steady CPU use. |
| SSH key | the default key, or upload your own |

Then:
1. **Networking → Create static IP**, attached to the instance (free while attached).
2. **Networking → IPv4 Firewall**: keep SSH (22), restricted to your IP if you can, and add
   HTTPS (443). HTTP (80) is there by default: keep it, for certificates and the redirect.
3. **Snapshots → Automatic snapshots**: turn them on (a daily copy of the whole disk).

### EC2

**EC2 → Launch instance**:

| Setting | Value |
| --- | --- |
| Name | `gogame` |
| AMI | **Ubuntu Server 24.04 LTS, 64-bit (x86)**. Not Arm: KataGo's Linux release is x86 only. |
| Instance type | **m7i.xlarge** (4 vCPU, 16 GB, ~$145/month) is comfortable. `t3.xlarge` (same size, burstable) is a little cheaper; `t3.large` (8 GB) works for testing, with slower builds and a slower computer opponent. |
| Key pair | Create one and download the `.pem` file: you need it to log in. |
| Network | **Create security group** with three inbound rules: SSH (22) from **My IP**, HTTP (80) and HTTPS (443) from **Anywhere**. Nothing else: MongoDB, Redis and the app ports stay closed. |
| Storage | 40 GB **gp3** |

Then **EC2 → Elastic IPs → Allocate**, and **Associate** it with the instance. The public
address then never changes, even when the instance restarts. For daily disk snapshots, use
**EC2 → Lifecycle Manager**.

## 3. Domain and DNS

### With a domain from Cloudflare Registrar

Domains registered at Cloudflare always use Cloudflare's DNS (Route 53 is not needed).

1. **dash.cloudflare.com → Domain Registration → Register Domains**: buy the domain (at cost,
   about $10 a year for a `.com`).
2. **Your domain → DNS → Records → Add record**, twice. Here the site is on the bare domain
   (`example.com`) and the websockets on `ws.example.com`:

   | Type | Name | IPv4 address | Proxy status |
   | --- | --- | --- | --- |
   | A | `@` | the Elastic IP | **DNS only** (grey cloud) |
   | A | `ws` | the Elastic IP | **DNS only** (grey cloud) |

   Keep them **DNS only** until the site works with HTTPS (step 4); step 8 turns on
   Cloudflare's proxy. Use the **bare domain** for the site: the websocket name must be under
   the site's name, because the login cookie is set for the site's name and its subdomains
   (`ws.example.com` gets it, `ws.other.example.com` doesn't). And Cloudflare's free
   certificate for proxied names covers one level only (`*.example.com`), so a site on
   `play.example.com` would need `ws.play.example.com`, which only works unproxied or with
   Cloudflare's paid Advanced Certificate Manager.
3. Optional, for your contact address: **Email → Email Routing** forwards
   `contact@yourdomain` to your own inbox, for free. Use that address for `net.email`.

### With Route 53 instead

1. **Route 53 → Registered domains → Register domain**, or keep a domain you already own.
2. **Route 53 → Hosted zones** (created automatically for domains registered there; for
   another registrar, create one and set the registrar's name servers to the four it lists).
3. Create two **A records** pointing at the Elastic IP: `example.com` and
   `ws.example.com` (the websockets). Use your own names.

## 4. Install GoGame

Log in (Ubuntu's user is `ubuntu`):

```bash
ssh -i gogame.pem ubuntu@ELASTIC_IP
```

Get the code into `/opt/gogame`. For a private repository, use a GitHub personal access
token or a deploy key:

```bash
sudo git clone -b claude/zen-cannon-yv99ta https://github.com/prathpatel/GoGame /opt/gogame
```

Install. `--email` is for Let's Encrypt, which only works once the DNS records of step 3
point at this server:

```bash
sudo /opt/gogame/bin/install-server example.com --email you@example.com
```

The websockets go on `ws.example.com` (or name them with `--ws-domain`). It takes 20 to 40 minutes, mostly building. It ends with a short list of what is left:
email (step 5), the administrator account (step 7) and backups (step 6). Check it:

```bash
systemctl status gogame gogame-ws
```

and open `https://example.com`. Logs: `journalctl -u gogame -f`.

## 5. Email (SES)

1. **SES → Identities → Create identity → Domain**: your domain, with **Easy DKIM**. If the
   domain is in Route 53, SES publishes the three DKIM records itself. With Cloudflare, add
   the three `CNAME` records SES shows in **Cloudflare → DNS → Records**, each set to
   **DNS only** (grey cloud): proxied DKIM records don't work. Optionally add a DMARC
   record: a `TXT` record `_dmarc` with `v=DMARC1; p=none;`.
2. **SES → Account dashboard → Request production access**. New accounts are in a "sandbox"
   that only sends to verified addresses. Explain the use: account confirmation and password
   reset emails for a game website, sent only to people who sign up. Approval usually takes
   about a day.
3. **SES → SMTP settings → Create SMTP credentials**. Download the SMTP username and
   password. These are not your AWS access keys.
4. On the server, edit `/opt/gogame/conf/secrets.conf`:

   ```hocon
   mailer.primary {
     mock = false
     host = "email-smtp.REGION.amazonaws.com"   # shown on the SMTP settings page
     port = 587
     tls = true
     user = "SMTP USERNAME"
     password = "SMTP PASSWORD"
     sender = "GoGame <noreply@example.com>"
   }
   security.email_confirm.enabled = true
   ```

   then `sudo systemctl restart gogame`, and test with a password reset.

EC2 blocks outgoing port 25; port 587, used here, is fine.

## 6. Backups and alerts

Every night `gogame-backup.timer` (as the `gogame` user, never root) writes a snapshot of
the database into `/var/backups/gogame`, 14 days kept:

- **Consistent:** MongoDB runs as a one-member replica set, so `mongodump --oplog` also
  records the writes made while the dump runs. A restore brings the database back exactly as
  it was at one moment, across all collections.
- **Checked:** each backup is restored into a temporary database, and its accounts and games
  counted (`bin/restore-database --check`). A backup that can't be restored fails.
- **Disk space:** the backup fails when the disk is more than 85% full.

Settings go in `/etc/gogame/backup.env`. Run a backup by hand with
`sudo systemctl start gogame-backup`, and see the result with `journalctl -u gogame-backup -n 20`.

### Alerts

1. **Failed or missed backups, full disk:** create a free check at <https://healthchecks.io>
   (period 1 day, grace 2 hours) and add its ping URL:

   ```bash
   echo 'GOGAME_HEALTHCHECK_URL=https://hc-ping.com/YOUR-UUID' | sudo tee -a /etc/gogame/backup.env
   ```

   It emails you when a backup fails, or doesn't report at all.
2. **Site down:** a free uptime monitor, such as UptimeRobot or Better Stack, checking
   `https://example.com` every few minutes.
3. **Server trouble:** on Lightsail, **Metrics → Add alarm** (CPU, status check failures) with
   email notifications; on EC2, CloudWatch alarms on `StatusCheckFailed` and CPU.

### Copies off the server (S3)

1. **S3 → Create bucket** (keep "Block all public access" on). Optionally add a lifecycle rule
   that deletes old backups after, say, 90 days.
2. Permission to upload, limited to `s3:PutObject` on `arn:aws:s3:::YOUR-BUCKET/gogame/*`:
   - **EC2:** an IAM role with that policy (**IAM → Roles → Create role**, for EC2), then
     **EC2 → the instance → Actions → Security → Modify IAM role**.
   - **Lightsail** (no IAM roles): an IAM user with that policy and an access key, set up for
     the `gogame` user with `sudo -u gogame -H aws configure`.
3. On the server:

   ```bash
   sudo snap install aws-cli --classic
   ```

   ```bash
   echo 'GOGAME_BACKUP_S3=s3://YOUR-BUCKET/gogame' | sudo tee -a /etc/gogame/backup.env
   ```

   ```bash
   sudo systemctl start gogame-backup && journalctl -u gogame-backup -n 5
   ```

Keep a copy of `/opt/gogame/conf/secrets.conf` in your password manager: without its
`user.password.bpass.secret`, a restored database's passwords don't work.

### Restoring

Check a backup without changing anything:

```bash
sudo -u gogame /opt/gogame/bin/restore-database --check /var/backups/gogame/gogame-DATE.archive.gz
```

Replace the database with it:

```bash
sudo systemctl stop gogame gogame-ws
```

```bash
sudo -u gogame /opt/gogame/bin/restore-database /var/backups/gogame/gogame-DATE.archive.gz
```

```bash
sudo systemctl start gogame gogame-ws
```

Try a restore once on a spare server, so you know the steps before you need them.

## 7. Administrator account

Sign up on the site, then give the account the administrator role. Use your username in
lowercase, and the MongoDB password from `conf/secrets.conf`:

```bash
mongosh "mongodb://gogame:PASSWORD@127.0.0.1/gogame?authSource=admin" --eval 'db.user4.updateOne({_id: "yourname"}, {$set: {roles: ["ROLE_SUPER_ADMIN"]}})'
```

Then turn on **two-factor authentication** in that account's security settings. In
production, administrator roles only work on accounts with two-factor authentication.

## 8. Cloudflare's proxy (optional, for a Cloudflare domain)

With the **proxied** setting (orange cloud), visitors connect to Cloudflare, which forwards
to your server: it hides the server's address and absorbs attacks. Websockets work through
it, on the free plan too. Turn it on only after the site works with HTTPS:

1. **Cloudflare → SSL/TLS → Overview**: set the encryption mode to **Full (strict)**. With
   "Flexible", Cloudflare talks to your server over plain HTTP, the server redirects to
   HTTPS, and the page loops forever.
2. **DNS → Records**: switch both A records to **Proxied**.
3. Nothing to change on the server: `bin/install-server` already set nginx up to take the
   visitors' real addresses from Cloudflare (`/etc/nginx/conf.d/cloudflare-realip.conf`),
   so rate limits and security logs see the players, not Cloudflare.
4. Optional, so nobody can bypass Cloudflare: in the EC2 security group, replace the
   "Anywhere" sources of the HTTP and HTTPS rules with Cloudflare's ranges, listed at
   <https://www.cloudflare.com/ips/>.

Let's Encrypt renewals keep working through the proxy. The signup captcha (Turnstile, see
the production setup in the main README) is in the same Cloudflare account.

## Updating

```bash
cd /opt/gogame && sudo -u gogame git pull
```

```bash
sudo /opt/gogame/bin/install-server example.com
```

It rebuilds and restarts; the secrets and database stay. The running services can't change
the project folder (their sandbox makes it read-only), so the scripts root runs here are the
ones you pulled.

## Costs

Rough on-demand prices; check the [AWS Pricing Calculator](https://calculator.aws/) for your
region:

| Item | Monthly |
| --- | --- |
| **Lightsail** 16 GB plan: server, 320 GB disk, static IP and 6 TB transfer included | **$84** |
| or **EC2** m7i.xlarge (t3.xlarge is a bit less), plus ~$3 for a 40 GB disk and ~$4 for the Elastic IP | ~$150 |
| Domain: Cloudflare Registrar sells at cost (~$10/year for `.com`, DNS included); Route 53 adds $0.50/month per hosted zone | ~$1 |
| SES ($0.10 per 1,000 emails) | cents |
| Lightsail automatic snapshots or EC2 disk snapshots, S3 backups | a few dollars |
| Data transfer out: included on Lightsail; on EC2 $0.09/GB beyond the 100 GB/month free tier | |

On EC2, a 1-year Savings Plan lowers the instance price by roughly a third.

## Why one instance

AWS's managed services can replace parts of this later, but none is needed to start:

- **MongoDB:** Amazon DocumentDB is not fully MongoDB-compatible, so prefer MongoDB on the
  instance, or MongoDB Atlas (hosted on AWS) if you want it managed.
- **Redis:** ElastiCache works, but is a separate cost for a small amount of data.
- **HTTPS:** an Application Load Balancer with a free ACM certificate is the standard setup
  for several app servers. With one server, nginx and Let's Encrypt do the same for free.
- **A faster computer opponent:** a GPU instance (`g4dn.xlarge`) with the NVIDIA drivers and
  `KATAGO_BACKEND=cuda bin/setup-katago` makes KataGo much stronger per second, at several
  times the price.
