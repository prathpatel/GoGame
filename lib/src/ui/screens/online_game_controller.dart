import 'dart:async';
import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:web_socket_channel/web_socket_channel.dart';

import '../../domain/ai/beginner_ai.dart';
import '../../domain/board.dart';
import '../../domain/clock/clock_controller.dart';
import '../../domain/clock/time_control.dart';
import '../../domain/game_state.dart';
import '../../domain/models.dart';
import '../../domain/scoring.dart';
import '../../online/api_client.dart';
import '../../sgf/sgf.dart';
import 'game_view_model.dart';

class OnlineGameController extends GameController {
  final OnlineApiClient api;
  final OnlineGameInfo game;
  late final StoneColor _youColor;
  late GameUi _ui;
  WebSocketChannel? _channel;
  StreamSubscription<dynamic>? _subscription;
  Timer? _reconnectTimer;
  Timer? _tokenTimer;
  var _seq = 0;
  var _connected = false;
  var _connecting = false;
  var _visible = true;
  var _closed = false;
  String? _result;
  OnlineGameController? _ownedTestPeer;
  OnlineAutomatedOpponent? _ownedBot;

  OnlineGameController({required this.api, required this.game}) {
    final userId = api.session?.user.id;
    if (userId == null ||
        (userId != game.blackUserId && userId != game.whiteUserId)) {
      throw const ApiException(
          'not_a_player', 'This account is not a player in that game.');
    }
    _youColor =
        userId == game.blackUserId ? StoneColor.black : StoneColor.white;
    final control = _timeControlFromJson(game.timeControl);
    final clock = ClockController(control);
    _ui = GameUi(
      state: GameState.newGame(_gameConfig(game)),
      opponent: Opponent.online,
      youColor: _youColor,
      opponentName:
          _youColor == StoneColor.black ? game.whiteName : game.blackName,
      timeControl: control,
      blackClock: clock.black,
      whiteClock: clock.white,
      connectionStatus: 'Connecting…',
      canUndo: false,
    );
  }

  String get gameId => game.id;
  bool get isConnected => _connected;

  @override
  GameUi get ui => _ui;

  void ownTestOpponent(OnlineGameController peer, OnlineAutomatedOpponent bot) {
    _ownedTestPeer = peer;
    _ownedBot = bot;
  }

  Future<void> connect() async {
    if (_closed || _connecting || _connected) return;
    _connecting = true;
    _replaceUi(_ui.copyWith(connectionStatus: 'Connecting…'));
    try {
      final uri = await api.webSocketUri();
      final channel = WebSocketChannel.connect(uri);
      await channel.ready.timeout(const Duration(seconds: 10));
      if (_closed || !_visible) {
        await channel.sink.close();
        return;
      }
      _channel = channel;
      _subscription = channel.stream.listen(
        _onMessage,
        onError: (Object error, StackTrace stack) => _onDisconnected(),
        onDone: _onDisconnected,
        cancelOnError: true,
      );
      _connected = true;
      _connecting = false;
      _replaceUi(_ui.copyWith(connectionStatus: 'Connected'));
      _sendEnvelope('JOIN_GAME', payload: {'gameId': game.id}, seq: 0);
      _scheduleTokenReconnect();
    } on Object catch (error) {
      _connecting = false;
      _connected = false;
      _replaceUi(_ui.copyWith(
        connectionStatus: 'Reconnecting…',
        rejection: _friendlyError(error),
      ));
      _scheduleReconnect();
    }
  }

  void _onMessage(dynamic raw) {
    if (_closed) return;
    try {
      final text = raw is String ? raw : utf8.decode(raw as List<int>);
      final envelope = _asMap(jsonDecode(text));
      final type = envelope['type'] as String? ?? '';
      final payload = _asMapOrEmpty(envelope['payload']);
      switch (type) {
        case 'AUTHENTICATED':
        case 'PONG':
        case 'MOVE_ACCEPTED':
          return;
        case 'GAME_SNAPSHOT':
          _applySnapshot(payload);
          return;
        case 'MOVE_PLAYED':
          _applyMove(payload);
          return;
        case 'CLOCK_UPDATE':
          _applyClock(payload);
          return;
        case 'SCORING_STARTED':
        case 'SCORE_UPDATED':
          _applyScore(payload);
          return;
        case 'GAME_ENDED':
          _result = payload['result'] as String?;
          final score = payload['score'] is Map
              ? _scoreFromJson(_asMap(payload['score']))
              : _ui.score;
          _replaceUi(_ui.copyWith(
            state: _ui.state.copyWith(status: GameStatus.completed),
            score: score,
            connectionStatus: 'Game complete',
          ));
          return;
        case 'MOVE_REJECTED':
          _replaceUi(_ui.copyWith(
            rejection: _humanizeServerReason(
                payload['reason'] as String? ?? 'Move rejected'),
          ));
          return;
        case 'ERROR':
          final code = payload['code'] as String? ?? 'server_error';
          _replaceUi(
              _ui.copyWith(rejection: payload['message'] as String? ?? code));
          if (code == 'token_expired' || code == 'invalid_token') {
            unawaited(_reconnectNow());
          }
          return;
      }
    } on Object catch (error) {
      _replaceUi(
          _ui.copyWith(rejection: 'Could not read a server update: $error'));
    }
  }

  void _applySnapshot(Map<String, dynamic> json) {
    final rows = (json['board'] as List).cast<String>();
    final configJson = _asMap(json['config']);
    final config = _gameConfigFromSnapshot(configJson);
    final current = _color(json['toMove']);
    final history = (json['recentMoves'] as List? ?? const [])
        .map((move) => _moveFromJson(_asMap(move)))
        .toList(growable: false);
    final captures = _asMapOrEmpty(json['captures']);
    final lastSeq = _asMapOrEmpty(json['lastSeq']);
    _seq = (_number(lastSeq[_youColor.name]) ?? _seq).clamp(_seq, 1 << 53);
    final board = Board.fromRows(rows);
    final state = GameState(
      board: board,
      config: config,
      currentPlayer: current,
      moveNumber: _number(json['moveNumber']) ?? history.length,
      capturesByBlack: _number(captures['black']) ?? 0,
      capturesByWhite: _number(captures['white']) ?? 0,
      koPoint: null,
      previousHashes: const {},
      status: _status(json['status']),
      consecutivePasses: _trailingPasses(history),
      lastMove: history.isEmpty ? null : history.last,
      history: history,
    );
    final wireHash = json['stateHash'] as String?;
    final hashMatches = wireHash == null || wireHash == _stateHash(state);
    final clock = _clockValues(json['clock']);
    _replaceUi(_ui.copyWith(
      state: state,
      deadStones: _points(json['deadStones']),
      score: state.status == GameStatus.scoring
          ? Scoring.score(state, deadStones: _points(json['deadStones']))
          : null,
      blackClock: clock.$1,
      whiteClock: clock.$2,
      rejection: hashMatches ? null : 'Server state verification failed.',
      connectionStatus: 'Connected',
    ));
  }

  void _applyMove(Map<String, dynamic> json) {
    final move = _moveFromJson(_asMap(json['move']));
    var board = _ui.state.board;
    if (move.type == MoveType.placeStone && move.point != null) {
      board = board.setCell(move.point!, CellState.of(move.player));
      board = board.setMany(
          move.captured.map((point) => MapEntry(point, CellState.empty)));
    }
    final blackCaptures = _ui.state.capturesByBlack +
        (move.player == StoneColor.black ? move.captured.length : 0);
    final whiteCaptures = _ui.state.capturesByWhite +
        (move.player == StoneColor.white ? move.captured.length : 0);
    final passes =
        move.type == MoveType.pass ? _ui.state.consecutivePasses + 1 : 0;
    final history = [..._ui.state.history, move];
    final next = _ui.state.copyWith(
      board: board,
      currentPlayer: _color(json['toMove']),
      moveNumber: move.moveNumber,
      capturesByBlack: blackCaptures,
      capturesByWhite: whiteCaptures,
      koPoint: null,
      consecutivePasses: passes,
      lastMove: move,
      history: history,
      status:
          move.type == MoveType.resign ? GameStatus.resigned : _ui.state.status,
    );
    final wireHash = json['stateHash'] as String?;
    _replaceUi(_ui.copyWith(
      state: next,
      rejection: wireHash == null || wireHash == _stateHash(next)
          ? null
          : 'Move state verification failed; reconnecting.',
      pendingPoint: null,
    ));
    if (wireHash != null && wireHash != _stateHash(next)) {
      unawaited(_reconnectNow());
    }
  }

  void _applyClock(Map<String, dynamic> json) {
    final values = _clockValues(json);
    _replaceUi(_ui.copyWith(blackClock: values.$1, whiteClock: values.$2));
  }

  void _applyScore(Map<String, dynamic> json) {
    final dead = _points(json['deadStones']);
    _replaceUi(_ui.copyWith(
      state: _ui.state.copyWith(status: GameStatus.scoring),
      deadStones: dead,
      score: json['score'] is Map
          ? _scoreFromJson(_asMap(json['score']))
          : Scoring.score(_ui.state, deadStones: dead),
    ));
  }

  (ClockSnapshot, ClockSnapshot) _clockValues(Object? raw) {
    final json = _asMapOrEmpty(raw);
    final fallback = ClockController(_ui.timeControl);
    return (
      json['black'] is Map
          ? ClockSnapshot.fromJson(_asMap(json['black']))
          : fallback.black,
      json['white'] is Map
          ? ClockSnapshot.fromJson(_asMap(json['white']))
          : fallback.white,
    );
  }

  int _nextSeq() => ++_seq;

  void _sendEnvelope(String type, {Map<String, Object?>? payload, int? seq}) {
    final channel = _channel;
    if (!_connected || channel == null) {
      _replaceUi(_ui.copyWith(rejection: 'Waiting for the server…'));
      return;
    }
    channel.sink.add(jsonEncode({
      'type': type,
      'seq': seq ?? _nextSeq(),
      'gameId': game.id,
      if (payload != null) 'payload': payload,
    }));
  }

  @override
  void tap(Point point) {
    if (_ui.state.status == GameStatus.scoring) {
      toggleDead(point);
      return;
    }
    if (_ui.state.status != GameStatus.active ||
        _ui.state.currentPlayer != _youColor) {
      return;
    }
    if (!_ui.state.board.inBounds(point) ||
        _ui.state.board.cellAt(point) != CellState.empty) {
      _replaceUi(_ui.copyWith(rejection: 'Point already occupied'));
      return;
    }
    _sendEnvelope('PLAY_MOVE', payload: {'row': point.row, 'col': point.col});
  }

  @override
  void pass() {
    if (_ui.state.status == GameStatus.active &&
        _ui.state.currentPlayer == _youColor) {
      _sendEnvelope('PASS');
    }
  }

  @override
  void resign() => _sendEnvelope('RESIGN');

  @override
  void toggleDead(Point point) {
    if (_ui.state.status == GameStatus.scoring) {
      _sendEnvelope('MARK_DEAD_GROUP',
          payload: {'row': point.row, 'col': point.col});
    }
  }

  @override
  void confirmScore() => _sendEnvelope('CONFIRM_SCORE');

  @override
  void resumePlay() => _sendEnvelope('DISPUTE_SCORE');

  @override
  void undo() => _replaceUi(
      _ui.copyWith(rejection: 'Undo is unavailable in online games.'));

  @override
  void cancelPending() {}

  @override
  String exportSgf() => Sgf.export(_ui.state,
      score: _ui.score, result: _result ?? _ui.score?.resultString);

  @override
  void setVisible(bool visible) {
    _visible = visible;
    if (visible) {
      unawaited(connect());
    } else {
      _reconnectTimer?.cancel();
    }
  }

  void _onDisconnected() {
    if (_closed) return;
    _connected = false;
    _connecting = false;
    _channel = null;
    _subscription = null;
    _replaceUi(_ui.copyWith(connectionStatus: 'Reconnecting…'));
    _scheduleReconnect();
  }

  void _scheduleReconnect() {
    if (_closed || !_visible || _ui.state.status == GameStatus.completed) {
      return;
    }
    _reconnectTimer?.cancel();
    _reconnectTimer = Timer(const Duration(seconds: 2), connect);
  }

  void _scheduleTokenReconnect() {
    _tokenTimer?.cancel();
    final expiry = api.accessTokenExpiresAt;
    if (expiry == null) return;
    final untilRefresh =
        expiry.difference(DateTime.now().toUtc()).inMilliseconds -
            const Duration(seconds: 30).inMilliseconds;
    final delay = Duration(milliseconds: untilRefresh);
    _tokenTimer =
        Timer(delay.isNegative ? Duration.zero : delay, _reconnectNow);
  }

  Future<void> _reconnectNow() async {
    await _subscription?.cancel();
    await _channel?.sink.close();
    _subscription = null;
    _channel = null;
    _connected = false;
    _connecting = false;
    await connect();
  }

  void _replaceUi(GameUi next) {
    if (_closed) return;
    _ui = next;
    notifyListeners();
  }

  @override
  void dispose() {
    if (_closed) return;
    _closed = true;
    _reconnectTimer?.cancel();
    _tokenTimer?.cancel();
    unawaited(_subscription?.cancel());
    unawaited(_channel?.sink.close());
    _ownedBot?.dispose();
    _ownedTestPeer?.dispose();
    _ownedTestPeer?.api.close();
    super.dispose();
  }
}

class OnlineAutomatedOpponent {
  final OnlineGameController controller;
  final BeginnerAi _ai = BeginnerAi();
  var _thinking = false;
  var _disposed = false;
  var _lastAttemptedMove = -1;

  OnlineAutomatedOpponent(this.controller) {
    controller.addListener(_onUpdate);
    _onUpdate();
  }

  void _onUpdate() {
    if (_disposed || _thinking || !controller.isConnected) return;
    final ui = controller.ui;
    if (ui.state.status == GameStatus.scoring) {
      _thinking = true;
      Future<void>.delayed(const Duration(milliseconds: 600), () {
        if (!_disposed) controller.confirmScore();
        _thinking = false;
      });
      return;
    }
    if (ui.state.status != GameStatus.active ||
        ui.state.currentPlayer != ui.youColor) {
      return;
    }
    _thinking = true;
    final state = ui.state;
    unawaited(() async {
      await Future<void>.delayed(const Duration(milliseconds: 450));
      if (_disposed || controller.ui.state.moveNumber != state.moveNumber) {
        _thinking = false;
        return;
      }
      if (_lastAttemptedMove == state.moveNumber && ui.rejection != null) {
        controller.pass();
      } else {
        _lastAttemptedMove = state.moveNumber;
        final intent = _ai.chooseMove(state);
        if (intent.type == MoveType.placeStone && intent.point != null) {
          controller.tap(intent.point!);
        } else {
          controller.pass();
        }
      }
      _thinking = false;
    }());
  }

  void dispose() {
    _disposed = true;
    controller.removeListener(_onUpdate);
  }
}

GameConfig _gameConfig(OnlineGameInfo game) => GameConfig(
      boardSize: game.boardSize,
      ruleset: _ruleset(game.ruleset),
      komi: game.komi,
      handicap: game.handicap,
      allowSuicide: RulesetDefaults.of(_ruleset(game.ruleset)).allowSuicide,
      superkoMode: RulesetDefaults.of(_ruleset(game.ruleset)).superkoMode,
    );

GameConfig _gameConfigFromSnapshot(Map<String, dynamic> json) {
  final ruleset = _ruleset(json['ruleset'] as String? ?? 'chinese');
  return GameConfig(
    boardSize: _number(json['boardSize']) ?? 9,
    ruleset: ruleset,
    komi:
        (json['komi'] as num?)?.toDouble() ?? RulesetDefaults.of(ruleset).komi,
    handicap: _number(json['handicap']) ?? 0,
    allowSuicide: json['allowSuicide'] as bool? ??
        RulesetDefaults.of(ruleset).allowSuicide,
    superkoMode: _superko(json['superkoMode'] as String?),
  );
}

TimeControl _timeControlFromJson(Map<String, dynamic> json) {
  final main = _number(json['mainSeconds']) ?? 0;
  return switch (json['kind'] as String? ?? 'none') {
    'absolute' => TimeControl.absolute(mainSeconds: main),
    'fischer' => TimeControl.fischer(
        mainSeconds: main,
        incrementSeconds: _number(json['incrementSeconds']) ?? 0),
    'byo_yomi' => TimeControl.byoYomi(
        mainSeconds: main,
        periods: _number(json['periods']) ?? 0,
        periodSeconds: _number(json['periodSeconds']) ?? 0),
    'canadian' => TimeControl.canadian(
        mainSeconds: main,
        stonesPerPeriod: _number(json['stonesPerPeriod']) ?? 0,
        periodSeconds: _number(json['periodSeconds']) ?? 0),
    _ => const TimeControl.none(),
  };
}

Move _moveFromJson(Map<String, dynamic> json) {
  final kind = switch (json['kind'] as String? ?? 'place') {
    'pass' => MoveType.pass,
    'resign' => MoveType.resign,
    _ => MoveType.placeStone,
  };
  return Move(
    moveNumber: _number(json['number'] ?? json['moveNumber']) ?? 0,
    player: _color(json['player']),
    type: kind,
    point: json['point'] is Map ? _point(_asMap(json['point'])) : null,
    captured: _points(json['captured']).toList(growable: false),
  );
}

ScoreResult _scoreFromJson(Map<String, dynamic> json) => ScoreResult(
      blackStones: _number(json['blackStones']) ?? 0,
      whiteStones: _number(json['whiteStones']) ?? 0,
      blackTerritory: _number(json['blackTerritory']) ?? 0,
      whiteTerritory: _number(json['whiteTerritory']) ?? 0,
      blackPrisoners: _number(json['blackPrisoners']) ?? 0,
      whitePrisoners: _number(json['whitePrisoners']) ?? 0,
      neutral: _number(json['neutral']) ?? 0,
      komi: (json['komi'] as num?)?.toDouble() ?? 0,
      method: json['method'] == 'territory'
          ? ScoringMethod.territory
          : ScoringMethod.area,
    );

Set<Point> _points(Object? raw) {
  if (raw is! List) return const {};
  return raw.map((item) => _point(_asMap(item))).toSet();
}

Point _point(Map<String, dynamic> json) =>
    Point(_number(json['row']) ?? -1, _number(json['col']) ?? -1);

StoneColor _color(Object? value) {
  if (value == 'black' || value == 1) return StoneColor.black;
  return StoneColor.white;
}

GameStatus _status(Object? value) => switch (value) {
      'scoring' => GameStatus.scoring,
      'completed' => GameStatus.completed,
      'resigned' => GameStatus.resigned,
      _ => GameStatus.active,
    };

Ruleset _ruleset(String value) => switch (value) {
      'japanese' => Ruleset.japanese,
      'korean' => Ruleset.korean,
      'aga' => Ruleset.aga,
      'ing' => Ruleset.ing,
      'new_zealand' => Ruleset.newZealand,
      'tromp_taylor' => Ruleset.trompTaylor,
      _ => Ruleset.chinese,
    };

SuperkoMode _superko(String? value) => switch (value) {
      'none' => SuperkoMode.none,
      'situational' => SuperkoMode.situational,
      _ => SuperkoMode.positional,
    };

int _trailingPasses(List<Move> moves) {
  var count = 0;
  for (final move in moves.reversed) {
    if (move.type != MoveType.pass) break;
    count++;
  }
  return count;
}

String _stateHash(GameState state) {
  final bytes = <int>[
    state.board.size,
    state.currentPlayer == StoneColor.black ? 1 : 2,
  ];
  for (var row = 0; row < state.board.size; row++) {
    for (var col = 0; col < state.board.size; col++) {
      bytes.add(state.board.cellAtRC(row, col).index);
    }
  }
  return sha256.convert(bytes).toString();
}

int? _number(Object? value) => value is num ? value.toInt() : null;

Map<String, dynamic> _asMap(Object? value) {
  if (value is Map<String, dynamic>) return value;
  if (value is Map) return Map<String, dynamic>.from(value);
  throw const FormatException('Expected an object');
}

Map<String, dynamic> _asMapOrEmpty(Object? value) =>
    value is Map ? Map<String, dynamic>.from(value) : const {};

String _humanizeServerReason(String reason) => switch (reason) {
      'not_your_turn' => 'Wait for your opponent.',
      'occupied' => 'Point already occupied.',
      'suicide' => 'Suicide is not allowed.',
      'ko' => 'Ko: cannot retake immediately.',
      'superko' => 'Superko: position would repeat.',
      'duplicate_or_stale_seq' => 'Move was already received; reconnecting.',
      _ => reason.replaceAll('_', ' '),
    };

String _friendlyError(Object error) {
  if (error is ApiException) return error.message;
  if (error is TimeoutException) return 'The backend did not respond in time.';
  return 'Could not connect to the backend.';
}
