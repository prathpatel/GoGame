import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:go_game/src/domain/models.dart';
import 'package:go_game/src/online/api_client.dart';
import 'package:go_game/src/ui/screens/online_game_controller.dart';
import 'package:web_socket_channel/web_socket_channel.dart';

const _runLive = bool.fromEnvironment('LIVE_BACKEND_TESTS');

void main() {
  test(
    'two guests match and exchange a server-authoritative move',
    () async {
      final first = OnlineApiClient(
        baseUrl: 'http://127.0.0.1:8080',
        storage: MemorySessionStorage(),
      );
      final second = OnlineApiClient(
        baseUrl: 'http://127.0.0.1:8080',
        storage: MemorySessionStorage(),
      );
      WebSocketChannel? firstSocket;
      WebSocketChannel? secondSocket;
      try {
        await first.checkReady();
        final stamp = DateTime.now().microsecondsSinceEpoch;
        final firstSession = await first.signInGuest('Smoke A $stamp');
        final secondSession = await second.signInGuest('Smoke B $stamp');
        final firstTicket = await first.enqueue(boardSize: 9);
        final secondTicket = await second.enqueue(boardSize: 9);
        final firstMatch = await _matched(first, firstTicket.id);
        final secondMatch = await _matched(second, secondTicket.id);
        expect(firstMatch.gameId, isNotNull);
        expect(secondMatch.gameId, firstMatch.gameId);

        final game = await first.getGame(firstMatch.gameId!);
        expect(game.boardSize, 9);
        expect(game.blackUserId, isNotEmpty);
        expect(game.whiteUserId, isNotEmpty);

        firstSocket = WebSocketChannel.connect(await first.webSocketUri());
        secondSocket = WebSocketChannel.connect(await second.webSocketUri());
        await Future.wait([firstSocket.ready, secondSocket.ready]);
        final firstEvents = StreamIterator<dynamic>(firstSocket.stream);
        final secondEvents = StreamIterator<dynamic>(secondSocket.stream);
        _join(firstSocket, game.id);
        _join(secondSocket, game.id);
        await _next(firstEvents, 'GAME_SNAPSHOT');
        await _next(secondEvents, 'GAME_SNAPSHOT');

        final blackSocket = game.blackUserId == firstSession.user.id
            ? firstSocket
            : secondSocket;
        blackSocket.sink.add(jsonEncode({
          'type': 'PLAY_MOVE',
          'seq': 1,
          'gameId': game.id,
          'payload': {'row': 4, 'col': 4},
        }));
        final firstMove = await _next(firstEvents, 'MOVE_PLAYED');
        final secondMove = await _next(secondEvents, 'MOVE_PLAYED');
        expect(_payload(firstMove)['stateHash'], isNotEmpty);
        expect(_payload(secondMove)['toMove'], 'white');
        expect(secondSession.user.id, isNot(firstSession.user.id));
      } finally {
        await firstSocket?.sink.close();
        await secondSocket?.sink.close();
        first.close();
        second.close();
      }
    },
    skip: !_runLive,
    timeout: const Timeout(Duration(seconds: 30)),
  );

  test(
    'online game controllers reconcile the live server state',
    () async {
      final first = OnlineApiClient(
        baseUrl: 'http://127.0.0.1:8080',
        storage: MemorySessionStorage(),
      );
      final second = OnlineApiClient(
        baseUrl: 'http://127.0.0.1:8080',
        storage: MemorySessionStorage(),
      );
      OnlineGameController? firstController;
      OnlineGameController? secondController;
      OnlineAutomatedOpponent? bot;
      try {
        final stamp = DateTime.now().microsecondsSinceEpoch;
        await first.signInGuest('Controller A $stamp');
        await second.signInGuest('Controller B $stamp');
        final firstTicket = await first.enqueue(boardSize: 9);
        final secondTicket = await second.enqueue(boardSize: 9);
        final firstMatch = await _matched(first, firstTicket.id);
        await _matched(second, secondTicket.id);
        final game = await first.getGame(firstMatch.gameId!);

        firstController = OnlineGameController(api: first, game: game);
        secondController = OnlineGameController(api: second, game: game);
        await Future.wait([
          firstController.connect(),
          secondController.connect(),
        ]);
        await _waitUntil(() =>
            firstController!.isConnected && secondController!.isConnected);

        final black = firstController.ui.youColor == StoneColor.black
            ? firstController
            : secondController;
        black.tap(const Point(4, 4));
        await _waitUntil(() =>
            firstController!.ui.state.moveNumber == 1 &&
            secondController!.ui.state.moveNumber == 1);

        expect(firstController.ui.state.board.cellAtRC(4, 4), CellState.black);
        expect(secondController.ui.state.board.cellAtRC(4, 4), CellState.black);
        expect(firstController.ui.state.currentPlayer, StoneColor.white);
        expect(firstController.ui.rejection, isNull);
        expect(secondController.ui.rejection, isNull);

        final white = firstController.ui.youColor == StoneColor.white
            ? firstController
            : secondController;
        bot = OnlineAutomatedOpponent(white);
        await _waitUntil(() =>
            firstController!.ui.state.moveNumber >= 2 &&
            secondController!.ui.state.moveNumber >= 2);
        expect(firstController.ui.state.currentPlayer, StoneColor.black);
        expect(secondController.ui.state.currentPlayer, StoneColor.black);
      } finally {
        bot?.dispose();
        firstController?.dispose();
        secondController?.dispose();
        first.close();
        second.close();
      }
    },
    skip: !_runLive,
    timeout: const Timeout(Duration(seconds: 30)),
  );
}

Future<MatchTicket> _matched(OnlineApiClient api, String ticketId) async {
  for (var attempt = 0; attempt < 15; attempt++) {
    final ticket = await api.getTicket(ticketId);
    if (ticket.status == 'matched') return ticket;
    await Future<void>.delayed(const Duration(seconds: 1));
  }
  throw TimeoutException('Matchmaking did not complete');
}

void _join(WebSocketChannel socket, String gameId) {
  socket.sink.add(jsonEncode({
    'type': 'JOIN_GAME',
    'seq': 0,
    'payload': {'gameId': gameId},
  }));
}

Future<Map<String, dynamic>> _next(
    StreamIterator<dynamic> events, String type) async {
  while (await events.moveNext().timeout(const Duration(seconds: 10))) {
    final raw = events.current;
    final decoded =
        jsonDecode(raw is String ? raw : utf8.decode(raw as List<int>));
    final event = Map<String, dynamic>.from(decoded as Map);
    if (event['type'] == type) return event;
  }
  throw StateError('Socket closed before $type');
}

Map<String, dynamic> _payload(Map<String, dynamic> event) =>
    Map<String, dynamic>.from(event['payload'] as Map);

Future<void> _waitUntil(bool Function() condition) async {
  final deadline = DateTime.now().add(const Duration(seconds: 10));
  while (!condition() && DateTime.now().isBefore(deadline)) {
    await Future<void>.delayed(const Duration(milliseconds: 50));
  }
  if (!condition()) throw TimeoutException('Controller state did not update');
}
