# Hosting GoGame on AWS

The simplest setup that works well for a Go server of any modest size: **one EC2 instance**
runs everything: nginx, lila, lila-ws, KataGo, MongoDB and Redis. Around it, AWS provides
the address (Elastic IP), DNS (Route 53), email (SES) and off-server backups (S3).

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

## 2. The server (EC2)

**EC2 → Launch instance**:

| Setting | Value |
| --- | --- |
| Name | `gogame` |
| AMI | **Ubuntu Server 24.04 LTS, 64-bit (x86)**. Not Arm: KataGo's Linux release is x86 only. |
| Instance type | **m7i.xlarge** (4 vCPU, 16 GB) is comfortable. `t3.xlarge` (same size, burstable) is a little cheaper; `t3.large` (8 GB) works for testing, with slower builds and a slower computer opponent. |
| Key pair | Create one and download the `.pem` file: you need it to log in. |
| Network | **Create security group** with three inbound rules: SSH (22) from **My IP**, HTTP (80) and HTTPS (443) from **Anywhere**. Nothing else: MongoDB, Redis and the app ports stay closed. |
| Storage | 40 GB **gp3** |

Then **EC2 → Elastic IPs → Allocate**, and **Associate** it with the instance. The public
address then never changes, even when the instance restarts.

## 3. Domain and DNS (Route 53)

1. **Route 53 → Registered domains → Register domain**, or keep a domain you already own.
2. **Route 53 → Hosted zones** (created automatically for domains registered there; for
   another registrar, create one and set the registrar's name servers to the four it lists).
3. Create two **A records** pointing at the Elastic IP: `go.example.com` and
   `ws.go.example.com` (the websockets). Use your own names.

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
sudo /opt/gogame/bin/install-server go.example.com --email you@example.com
```

It takes 20 to 40 minutes, mostly building. It ends with a short list of what is left:
email (step 5), the administrator account (step 7) and backups (step 6). Check it:

```bash
systemctl status gogame gogame-ws
```

and open `https://go.example.com`. Logs: `journalctl -u gogame -f`.

## 5. Email (SES)

1. **SES → Identities → Create identity → Domain**: your domain, with **Easy DKIM**. If the
   domain is in Route 53, SES publishes the three DKIM records itself; otherwise add the
   CNAME records it shows at your registrar. Optionally add a DMARC record: a `TXT` record
   `_dmarc.go.example.com` with `v=DMARC1; p=none;`.
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
     sender = "GoGame <noreply@go.example.com>"
   }
   security.email_confirm.enabled = true
   ```

   then `sudo systemctl restart gogame`, and test with a password reset.

EC2 blocks outgoing port 25; port 587, used here, is fine.

## 6. Backups

Every night `gogame-backup.timer` dumps the database into `/var/backups/gogame` (14 days
kept). To also copy it off the server, to S3:

1. **S3 → Create bucket** (keep "Block all public access" on). Optionally add a lifecycle rule
   that deletes old backups after, say, 90 days.
2. **IAM → Roles → Create role**, for **EC2**, with a policy that allows `s3:PutObject` on
   `arn:aws:s3:::YOUR-BUCKET/gogame/*`. Then **EC2 → the instance → Actions → Security →
   Modify IAM role**, and pick it.
3. On the server:

   ```bash
   sudo snap install aws-cli --classic
   ```

   ```bash
   sudo mkdir -p /etc/gogame && echo 'GOGAME_BACKUP_S3=s3://YOUR-BUCKET/gogame' | sudo tee /etc/gogame/backup.env
   ```

   ```bash
   sudo systemctl start gogame-backup && journalctl -u gogame-backup -n 5
   ```

Also:
- **EC2 → Lifecycle Manager**: a daily snapshot policy for the instance's disk saves the
  whole server, KataGo and configuration included.
- Keep a copy of `/opt/gogame/conf/secrets.conf` in your password manager. Without its
  `user.password.bpass.secret`, a restored database's passwords don't work.

To restore a dump:

```bash
cd /opt/gogame && mongorestore --uri "$(grep -oE 'mongodb://[^"]+' conf/secrets.conf | head -1)" --archive=/var/backups/gogame/gogame-DATE.archive.gz --gzip --drop
```

## 7. Administrator account

Sign up on the site, then give the account the administrator role. Use your username in
lowercase, and the MongoDB password from `conf/secrets.conf`:

```bash
mongosh "mongodb://gogame:PASSWORD@127.0.0.1/gogame?authSource=admin" --eval 'db.user4.updateOne({_id: "yourname"}, {$set: {roles: ["ROLE_SUPER_ADMIN"]}})'
```

Then turn on **two-factor authentication** in that account's security settings. In
production, administrator roles only work on accounts with two-factor authentication.

## Updating

```bash
cd /opt/gogame && sudo -u gogame git pull
```

```bash
sudo /opt/gogame/bin/install-server go.example.com
```

It rebuilds and restarts; the secrets and database stay.

## Costs

Rough on-demand prices; check the [AWS Pricing Calculator](https://calculator.aws/) for your
region:

| Item | Monthly |
| --- | --- |
| m7i.xlarge (t3.xlarge is a bit less, t3.large about half) | ~$145 |
| 40 GB gp3 disk | ~$3 |
| Elastic IP (public IPv4) | ~$4 |
| Route 53 hosted zone, plus the domain (~$15/year) | ~$1.50 |
| SES ($0.10 per 1,000 emails) | cents |
| S3 backups and disk snapshots | a few dollars |
| Data transfer out (beyond the 100 GB/month free tier) | $0.09/GB |

A 1-year Savings Plan lowers the instance price by roughly a third. Amazon **Lightsail**
offers the same kind of Ubuntu server at a fixed monthly price (16 GB plans exist) with the
IP and some transfer included. `bin/install-server` works there too; email and backups are
set up the same way.

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
