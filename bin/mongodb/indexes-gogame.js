// The indexes of the collections GoGame uses (a subset of indexes.js, which also covers
// lichess features this fork removed). They include the unique index on user emails and the
// expiring indexes that delete old sessions, tokens, notifications and seeks. Safe to run
// again: existing indexes are left as they are.
//
//   mongosh gogame bin/mongodb/indexes-gogame.js
// (bin/install-server runs it on every install.)

db.bookmark.createIndex({ g: 1 });
db.bookmark.createIndex({ u: 1 });
db.bookmark.createIndex({ u: 1, d: -1 });
db.cache.createIndex({ e: 1 }, { expireAfterSeconds: 0 });
db.challenge.createIndex({ status: 1, 'challenger.id': 1, createdAt: 1 });
db.challenge.createIndex({ status: 1, 'destUser.id': 1, createdAt: 1 });
db.challenge.createIndex({ expiresAt: 1 });
db.challenge.createIndex(
  { seenAt: 1 },
  { partialFilterExpression: { status: 10, 'timeControl.l': { $exists: true } } },
);
db.challenge_bulk.createIndex({ pairAt: 1 });
db.challenge_bulk.createIndex(
  { startClocksAt: 1 },
  { partialFilterExpression: { startClocksAt: { $exists: true } } },
);
db.challenge_bulk.createIndex({ by: 1, pairAt: -1 });
db.chat_timeout.createIndex({ expiresAt: -1 }, { partialFilterExpression: { expiresAt: { $exists: 1 } } });
db.chat_timeout.createIndex(
  { chat: 1, expiresAt: -1 },
  { partialFilterExpression: { expiresAt: { $exists: 1 } } },
);
db.chat_timeout.createIndex({ user: 1, createdAt: -1 });
db.firewall.createIndex({ date: 1 }, { expireAfterSeconds: 7776000 });
db.game5.createIndex({ ca: -1 });
db.game5.createIndex({ us: 1, ca: -1 });
db.game5.createIndex({ 'pgni.user': 1, 'pgni.ca': -1 }, { sparse: 1 });
db.game5.createIndex({ ck: 1 }, { sparse: 1 });
db.game5.createIndex({ pl: 1 }, { sparse: true });
db.game5.createIndex({ 'pgni.h': 1 }, { sparse: true });
db.msg_msg.createIndex({ tid: 1, date: -1 });
db.msg_thread.createIndex({ users: 1, 'lastMsg.date': -1 });
db.msg_thread.createIndex({ users: 1 }, { partialFilterExpression: { 'lastMsg.read': false } });
db.msg_thread.createIndex({ users: 1, 'maskWith.date': -1 });
db.note.createIndex({ to: 1, date: -1 });
db.note.createIndex({ from: 1 }, { partialFilterExpression: { mod: false } });
db.note.createIndex(
  { _fts: 'text', _ftsx: 1, dox: 1, date: -1 },
  {
    partialFilterExpression: { s: true },
    weights: { from: 1, text: 1, to: 1 },
    default_language: 'english',
    language_override: 'language',
    textIndexVersion: 3,
  },
);
db.notify.createIndex({ notifies: 1, read: 1, createdAt: -1 });
db.notify.createIndex({ createdAt: 1 }, { expireAfterSeconds: 2592000 });
db.notify_pref.createIndex(
  { correspondenceEmail: 1 },
  { partialFilterExpression: { correspondenceEmail: true } },
);
db.oauth2_access_token.createIndex({ userId: 1 });
db.oauth2_access_token.createIndex({ expires: 1 }, { expireAfterSeconds: 0 });
db.oauth2_access_token.createIndex(
  { clientOrigin: 1, used: 1 },
  { partialFilterExpression: { clientOrigin: 'https://auth.taketaketake.com' } },
);
db.oauth2_authorization.createIndex({ expires: 1 }, { expireAfterSeconds: 0 });
db.print_ban.createIndex({ date: 1 }, { expireAfterSeconds: 7776000 });
db.ranking.createIndex({ expiresAt: 1 }, { expireAfterSeconds: 0 });
db.ranking.createIndex({ perf: 1, rating: -1 }, { partialFilterExpression: { stable: true } });
db.ranking.createIndex({ perf: 1, rating: -1, expiresAt: -1 }, { partialFilterExpression: { stable: true } });
db.relation.createIndex({ u1: 1 });
db.relation.createIndex({ u2: 1 });
db.relation_subs.createIndex({ s: 1 });
db.report2.createIndex({ room: 1, score: -1 }, { partialFilterExpression: { open: true } });
db.report2.createIndex(
  { 'inquiry.mod': 1 },
  { partialFilterExpression: { 'inquiry.mod': { $exists: true } } },
);
db.report2.createIndex({ user: 1 });
db.report2.createIndex({ 'atoms.by': 1 });
db.report2.createIndex({ room: 1, 'atoms.0.at': -1 });
db.report2.createIndex({ 'atoms.0.at': -1 });
db.report2.createIndex({ 'done.at': -1 }, { partialFilterExpression: { open: false } });
db.round_alarm.createIndex({ ringsAt: 1 });
db.round_alarm.createIndex({ expiresAt: 1 }, { expireAfterSeconds: 0 });
db.security.createIndex({ user: 1 });
db.security.createIndex({ ip: 1 });
db.security.createIndex({ fp: 1 }, { sparse: 1 });
db.seek.createIndex({ createdAt: -1 }, { expireAfterSeconds: 86400 });
db.seek_archive.createIndex({ archivedAt: 1 }, { expireAfterSeconds: 604800 });
db.seek_archive.createIndex({ gameId: 1 });
db.trophy.createIndex({ user: 1 });
db.user4.createIndex({ 'count.game': -1 });
db.user4.createIndex({ title: 1 }, { partialFilterExpression: { title: { $exists: 1 } } });
db.user4.createIndex({ email: 1 }, { unique: true, partialFilterExpression: { email: { $exists: 1 } } });
db.user4.createIndex({ roles: 1 }, { partialFilterExpression: { roles: { $exists: 1 } } });
db.user4.createIndex({ prevEmail: 1 }, { sparse: 1 });
db.user4.createIndex(
  { 'delete.requested': 1 },
  { partialFilterExpression: { 'delete.requested': { $exists: 1 } } },
);
db.user4.createIndex(
  { mustConfirmEmail: 1 },
  { partialFilterExpression: { mustConfirmEmail: { $exists: 1 } }, expireAfterSeconds: 3600 * 24 * 3 },
);

print(`indexes ready on ${db.getName()}`);
