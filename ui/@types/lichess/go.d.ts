// Global types shared by the UI packages.
// (Lichess took these from chessground; this Go fork defines them directly.)

declare global {
  type Color = 'white' | 'black';
  type Key = string;
  type FEN = string;
  type Role = string;
  type Piece = { role: Role; color: Color };
  type Dests = Map<Key, Key[]>;
}

export {};
