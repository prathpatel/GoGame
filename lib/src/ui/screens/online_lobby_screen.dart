import 'dart:async';

import 'package:flutter/material.dart';

import '../../data/profile_store.dart';
import '../../online/api_client.dart';
import '../components/zen_components.dart';
import 'online_game_controller.dart';

class OnlineLobbyScreen extends StatefulWidget {
  final OnlineApiClient api;
  final ProfileStore profile;
  final ValueChanged<OnlineGameController> onGameReady;
  final VoidCallback onBack;

  const OnlineLobbyScreen({
    super.key,
    required this.api,
    required this.profile,
    required this.onGameReady,
    required this.onBack,
  });

  @override
  State<OnlineLobbyScreen> createState() => _OnlineLobbyScreenState();
}

class _OnlineLobbyScreenState extends State<OnlineLobbyScreen> {
  late final TextEditingController _endpoint =
      TextEditingController(text: widget.api.baseUrl);
  var _boardSize = 9;
  var _busy = false;
  var _cancelled = false;
  String? _ticketId;
  OnlineApiClient? _peerApi;
  String? _peerTicketId;
  String? _status;
  String? _error;

  @override
  void dispose() {
    _cancelled = true;
    final ticket = _ticketId;
    if (ticket != null) unawaited(widget.api.cancelTicket(ticket));
    final peerApi = _peerApi;
    final peerTicket = _peerTicketId;
    if (peerApi != null) {
      if (peerTicket == null) {
        peerApi.close();
      } else {
        unawaited(peerApi
            .cancelTicket(peerTicket)
            .catchError((Object _) {})
            .whenComplete(peerApi.close));
      }
    }
    _endpoint.dispose();
    super.dispose();
  }

  Future<void> _testConnection() async {
    setState(() {
      _busy = true;
      _error = null;
      _status = 'Checking backend…';
    });
    try {
      await widget.api.setBaseUrl(_endpoint.text);
      await widget.api.checkReady();
      final session = await widget.api.ensureSession(widget.profile.value.name);
      if (!mounted) return;
      setState(() {
        _status =
            'Connected as ${session.user.displayName} · ${session.user.rating}';
      });
    } on Object catch (error) {
      if (!mounted) return;
      setState(() => _error = _message(error));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _findMatch({required bool testOpponent}) async {
    setState(() {
      _busy = true;
      _cancelled = false;
      _error = null;
      _status = 'Connecting…';
    });
    OnlineApiClient? peerApi;
    String? peerTicket;
    OnlineGameController? controller;
    OnlineGameController? unownedPeerController;
    try {
      await widget.api.setBaseUrl(_endpoint.text);
      await widget.api.checkReady();
      await widget.api.ensureSession(widget.profile.value.name);
      final ticket = await widget.api.enqueue(boardSize: _boardSize);
      _ticketId = ticket.id;

      if (testOpponent) {
        if (mounted) {
          setState(() => _status = 'Starting network test opponent…');
        }
        peerApi = widget.api.testPeer();
        _peerApi = peerApi;
        await peerApi.signInGuest('Network test bot');
        final second = await peerApi.enqueue(boardSize: _boardSize);
        peerTicket = second.id;
        _peerTicketId = peerTicket;
      }

      if (mounted) {
        setState(() => _status = testOpponent
            ? 'Pairing test game…'
            : 'Waiting for another player…');
      }
      final matched = await _waitForMatch(widget.api, ticket.id);
      if (_cancelled || !mounted) return;
      final gameId = matched.gameId;
      if (gameId == null) {
        throw const ApiException(
            'match_failed', 'The match completed without a game.');
      }
      final game = await widget.api.getGame(gameId);
      controller = OnlineGameController(api: widget.api, game: game);
      await controller.connect();

      if (peerApi != null && peerTicket != null) {
        final peerMatched = await _waitForMatch(peerApi, peerTicket);
        final peerGame = await peerApi.getGame(peerMatched.gameId ?? gameId);
        unownedPeerController =
            OnlineGameController(api: peerApi, game: peerGame);
        await unownedPeerController.connect();
        final bot = OnlineAutomatedOpponent(unownedPeerController);
        controller.ownTestOpponent(unownedPeerController, bot);
        unownedPeerController = null;
        peerApi = null;
        _peerApi = null;
        _peerTicketId = null;
      }

      _ticketId = null;
      if (!mounted || _cancelled) {
        controller.dispose();
        return;
      }
      widget.onGameReady(controller);
      controller = null;
    } on Object catch (error) {
      final ticket = _ticketId;
      _ticketId = null;
      if (ticket != null) {
        try {
          await widget.api.cancelTicket(ticket);
        } on Object {
          // It may already have been claimed by matchmaking.
        }
      }
      final pendingPeer = _peerApi;
      final pendingPeerTicket = _peerTicketId;
      _peerApi = null;
      _peerTicketId = null;
      if (pendingPeer != null && pendingPeerTicket != null) {
        try {
          await pendingPeer.cancelTicket(pendingPeerTicket);
        } on Object {
          // It may already have been claimed by matchmaking.
        }
      }
      controller?.dispose();
      unownedPeerController?.dispose();
      pendingPeer?.close();
      if (!identical(peerApi, pendingPeer)) peerApi?.close();
      if (!mounted || _cancelled) return;
      setState(() => _error = _message(error));
    } finally {
      if (mounted && !_cancelled) setState(() => _busy = false);
    }
  }

  Future<MatchTicket> _waitForMatch(
      OnlineApiClient api, String ticketId) async {
    final deadline = DateTime.now().add(const Duration(minutes: 10));
    while (!_cancelled && DateTime.now().isBefore(deadline)) {
      final ticket = await api.getTicket(ticketId);
      if (ticket.status == 'matched') return ticket;
      if (ticket.status == 'cancelled') {
        throw const ApiException('cancelled', 'Matchmaking was cancelled.');
      }
      await Future<void>.delayed(const Duration(seconds: 1));
    }
    throw const ApiException('timeout', 'No match was found in time.');
  }

  Future<void> _cancel() async {
    _cancelled = true;
    final ticket = _ticketId;
    final peerApi = _peerApi;
    final peerTicket = _peerTicketId;
    _ticketId = null;
    _peerApi = null;
    _peerTicketId = null;
    if (ticket != null) {
      try {
        await widget.api.cancelTicket(ticket);
      } on Object {
        // A ticket matched between the last poll and this cancellation.
      }
    }
    if (peerApi != null && peerTicket != null) {
      try {
        await peerApi.cancelTicket(peerTicket);
      } on Object {
        // It may already have been claimed by matchmaking.
      }
    }
    peerApi?.close();
    if (!mounted) return;
    setState(() {
      _busy = false;
      _status = 'Matchmaking cancelled.';
    });
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    return SingleChildScrollView(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              IconButton(
                  onPressed: _busy ? _cancel : widget.onBack,
                  icon: const Icon(Icons.arrow_back)),
              const SizedBox(width: 8),
              Text('Online play', style: text.headlineMedium),
            ],
          ),
          const SizedBox(height: 12),
          ZenCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text('Local backend', style: text.headlineSmall),
                const SizedBox(height: 8),
                TextField(
                  controller: _endpoint,
                  enabled: !_busy,
                  autocorrect: false,
                  keyboardType: TextInputType.url,
                  decoration: const InputDecoration(
                    labelText: 'API URL',
                    helperText:
                        'Android emulator default: http://10.0.2.2:8080',
                    border: OutlineInputBorder(),
                  ),
                ),
                const SizedBox(height: 12),
                OutlinedButton.icon(
                  onPressed: _busy ? null : _testConnection,
                  icon: const Icon(Icons.health_and_safety_outlined),
                  label: const Text('TEST CONNECTION'),
                ),
              ],
            ),
          ),
          const SizedBox(height: 12),
          ZenCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text('Casual match', style: text.headlineSmall),
                const SizedBox(height: 6),
                Text(
                  'Chinese rules · 10 minute clock',
                  style:
                      text.bodyMedium?.copyWith(color: scheme.onSurfaceVariant),
                ),
                const SizedBox(height: 12),
                SegmentedButton<int>(
                  segments: const [
                    ButtonSegment(value: 9, label: Text('9×9')),
                    ButtonSegment(value: 13, label: Text('13×13')),
                    ButtonSegment(value: 19, label: Text('19×19')),
                  ],
                  selected: {_boardSize},
                  onSelectionChanged: _busy
                      ? null
                      : (value) => setState(() => _boardSize = value.first),
                ),
                const SizedBox(height: 16),
                FilledButton.icon(
                  onPressed:
                      _busy ? null : () => _findMatch(testOpponent: false),
                  icon: const Icon(Icons.public),
                  label: const Text('FIND ANOTHER PLAYER'),
                ),
                const SizedBox(height: 8),
                OutlinedButton.icon(
                  onPressed:
                      _busy ? null : () => _findMatch(testOpponent: true),
                  icon: const Icon(Icons.smart_toy_outlined),
                  label: const Text('QUICK NETWORK TEST'),
                ),
                const SizedBox(height: 8),
                Text(
                  'Quick Network Test creates a second guest and drives it '
                  'through the same REST, matchmaking, WebSocket and database path.',
                  style:
                      text.labelSmall?.copyWith(color: scheme.onSurfaceVariant),
                ),
              ],
            ),
          ),
          if (_busy) ...[
            const SizedBox(height: 16),
            const LinearProgressIndicator(),
          ],
          if (_status != null) ...[
            const SizedBox(height: 12),
            Text(_status!,
                textAlign: TextAlign.center,
                style: text.bodyMedium?.copyWith(color: scheme.primary)),
          ],
          if (_error != null) ...[
            const SizedBox(height: 12),
            Text(_error!,
                textAlign: TextAlign.center,
                style: text.bodyMedium?.copyWith(color: scheme.error)),
          ],
        ],
      ),
    );
  }
}

String _message(Object error) {
  if (error is ApiException) return error.message;
  if (error is TimeoutException) return 'The backend did not respond in time.';
  return 'Could not reach the backend. Is it running at the API URL?';
}
