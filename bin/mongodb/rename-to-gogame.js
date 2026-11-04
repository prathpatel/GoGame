// Moves the data of the old "lichess" database into "gogame", the database GoGame now uses
// (mongodb.uri in conf/base.conf, mongo.uri in ws/src/main/resources/application.conf).
//
//   mongosh bin/mongodb/rename-to-gogame.js
//
// Stop lila and lila-ws first. Each collection keeps its documents and indexes. A collection
// that already exists in "gogame" is replaced only if it is empty (lila creates a few at
// startup); a non-empty one is left alone and reported.
const from = 'lichess';
const to = 'gogame';

const source = db.getSiblingDB(from);
const target = db.getSiblingDB(to);
const names = source.getCollectionNames().filter(n => !n.startsWith('system.'));

if (!names.length) {
  print(`Nothing to move: the "${from}" database is empty or does not exist.`);
} else {
  const existing = new Set(target.getCollectionNames());
  const moved = [];
  const kept = [];
  for (const name of names) {
    if (existing.has(name)) {
      if (target.getCollection(name).estimatedDocumentCount() > 0) {
        kept.push(name);
        continue;
      }
      target.getCollection(name).drop();
    }
    const res = db.adminCommand({ renameCollection: `${from}.${name}`, to: `${to}.${name}` });
    if (res.ok !== 1) throw new Error(`could not move ${name}: ${JSON.stringify(res)}`);
    moved.push(name);
  }
  print(`Moved ${moved.length} collections from "${from}" to "${to}".`);
  if (kept.length)
    print(
      `Not moved, because "${to}" already has data in them: ${kept.join(', ')}.\n` +
        `They are still in "${from}".`,
    );
}
