import 'dart:async';
import 'dart:convert';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

const _defaultApiBaseUrl = String.fromEnvironment(
  'API_BASE_URL',
  defaultValue: 'http://10.0.2.2:8080',
);

class ApiException implements Exception {
  final int? statusCode;
  final String code;
  final String message;

  const ApiException(this.code, this.message, {this.statusCode});

  @override
  String toString() => message;
}

class OnlineUser {
  final String id;
  final String displayName;
  final int rating;
  final bool isGuest;

  const OnlineUser({
    required this.id,
    required this.displayName,
    required this.rating,
    required this.isGuest,
  });

  factory OnlineUser.fromJson(Map<String, dynamic> json) => OnlineUser(
        id: json['id'] as String,
        displayName: json['displayName'] as String? ?? 'Player',
        rating: (json['rating'] as num?)?.toInt() ?? 1000,
        isGuest: json['isGuest'] as bool? ?? true,
      );

  Map<String, Object> toJson() => {
        'id': id,
        'displayName': displayName,
        'rating': rating,
        'isGuest': isGuest,
      };
}

class TokenPair {
  final String accessToken;
  final String refreshToken;
  final DateTime expiresAt;

  const TokenPair({
    required this.accessToken,
    required this.refreshToken,
    required this.expiresAt,
  });

  factory TokenPair.fromJson(Map<String, dynamic> json) => TokenPair(
        accessToken: json['accessToken'] as String,
        refreshToken: json['refreshToken'] as String,
        expiresAt: DateTime.parse(json['expiresAt'] as String).toUtc(),
      );

  Map<String, Object> toJson() => {
        'accessToken': accessToken,
        'refreshToken': refreshToken,
        'expiresAt': expiresAt.toIso8601String(),
      };
}

class OnlineSession {
  final OnlineUser user;
  final TokenPair tokens;

  const OnlineSession({required this.user, required this.tokens});
}

class MatchTicket {
  final String id;
  final String status;
  final String? gameId;

  const MatchTicket({
    required this.id,
    required this.status,
    required this.gameId,
  });

  factory MatchTicket.fromJson(Map<String, dynamic> json) => MatchTicket(
        id: json['id'] as String,
        status: json['status'] as String,
        gameId: json['gameId'] as String?,
      );
}

class OnlineGameInfo {
  final String id;
  final String blackUserId;
  final String whiteUserId;
  final String blackName;
  final String whiteName;
  final int boardSize;
  final String ruleset;
  final double komi;
  final int handicap;
  final Map<String, dynamic> timeControl;
  final String mode;

  const OnlineGameInfo({
    required this.id,
    required this.blackUserId,
    required this.whiteUserId,
    required this.blackName,
    required this.whiteName,
    required this.boardSize,
    required this.ruleset,
    required this.komi,
    required this.handicap,
    required this.timeControl,
    required this.mode,
  });

  factory OnlineGameInfo.fromJson(Map<String, dynamic> json) => OnlineGameInfo(
        id: json['id'] as String,
        blackUserId: json['blackUserId'] as String? ?? '',
        whiteUserId: json['whiteUserId'] as String? ?? '',
        blackName: json['blackName'] as String? ?? 'Black',
        whiteName: json['whiteName'] as String? ?? 'White',
        boardSize: (json['boardSize'] as num).toInt(),
        ruleset: json['ruleset'] as String? ?? 'chinese',
        komi: (json['komi'] as num?)?.toDouble() ?? 7.5,
        handicap: (json['handicap'] as num?)?.toInt() ?? 0,
        timeControl:
            Map<String, dynamic>.from(json['timeControl'] as Map? ?? const {}),
        mode: json['mode'] as String? ?? 'casual',
      );
}

abstract interface class SessionStorage {
  Future<String?> read(String key);
  Future<void> write(String key, String value);
  Future<void> delete(String key);
}

class SecureSessionStorage implements SessionStorage {
  final FlutterSecureStorage _storage;

  const SecureSessionStorage([this._storage = const FlutterSecureStorage()]);

  @override
  Future<String?> read(String key) => _storage.read(key: key);

  @override
  Future<void> write(String key, String value) =>
      _storage.write(key: key, value: value);

  @override
  Future<void> delete(String key) => _storage.delete(key: key);
}

class MemorySessionStorage implements SessionStorage {
  final Map<String, String> _values = {};

  @override
  Future<String?> read(String key) async => _values[key];

  @override
  Future<void> write(String key, String value) async => _values[key] = value;

  @override
  Future<void> delete(String key) async => _values.remove(key);
}

class OnlineApiClient {
  static const _endpointPreference = 'online.apiBaseUrl';
  static const _sessionKey = 'online.session';

  final http.Client _http;
  final SessionStorage _storage;
  final SharedPreferences? _preferences;
  String _baseUrl;
  OnlineSession? _session;
  Future<TokenPair>? _refreshInFlight;

  OnlineApiClient({
    required String baseUrl,
    http.Client? httpClient,
    SessionStorage? storage,
    SharedPreferences? preferences,
  })  : _baseUrl = _normalizeBaseUrl(baseUrl),
        _http = httpClient ?? http.Client(),
        _storage = storage ?? const SecureSessionStorage(),
        _preferences = preferences;

  static Future<OnlineApiClient> open() async {
    final preferences = await SharedPreferences.getInstance();
    return OnlineApiClient(
      baseUrl: preferences.getString(_endpointPreference) ?? _defaultApiBaseUrl,
      preferences: preferences,
    );
  }

  String get baseUrl => _baseUrl;
  OnlineSession? get session => _session;

  static String _normalizeBaseUrl(String raw) {
    final trimmed = raw.trim().replaceFirst(RegExp(r'/+$'), '');
    final uri = Uri.tryParse(trimmed);
    if (uri == null ||
        !uri.hasScheme ||
        !uri.hasAuthority ||
        (uri.scheme != 'http' && uri.scheme != 'https')) {
      throw const ApiException(
          'invalid_endpoint', 'Enter a valid http:// or https:// API URL.');
    }
    return trimmed;
  }

  Future<void> setBaseUrl(String value) async {
    final normalized = _normalizeBaseUrl(value);
    if (normalized == _baseUrl) return;
    _baseUrl = normalized;
    await _preferences?.setString(_endpointPreference, normalized);
    await clearSession();
  }

  Future<void> checkReady() async {
    final response =
        await _http.get(_uri('/readyz')).timeout(const Duration(seconds: 8));
    if (response.statusCode != 200) {
      throw _exceptionFor(response);
    }
  }

  Future<OnlineSession> ensureSession(String displayName) async {
    if (_session == null) await _restoreSession();
    if (_session != null) {
      try {
        await accessToken();
        return _session!;
      } on Object {
        await clearSession();
      }
    }
    return signInGuest(displayName);
  }

  Future<OnlineSession> signInGuest(String displayName) async {
    final response = await _send(
      'POST',
      '/auth/guest',
      body: {'displayName': displayName.trim()},
      authenticated: false,
    );
    final session = OnlineSession(
      user: OnlineUser.fromJson(_map(response['user'])),
      tokens: TokenPair.fromJson(_map(response['tokens'])),
    );
    await _saveSession(session);
    return session;
  }

  Future<String> accessToken() async {
    if (_session == null) await _restoreSession();
    final current = _session;
    if (current == null) {
      throw const ApiException('unauthenticated', 'Sign in to play online.');
    }
    if (current.tokens.expiresAt
        .isAfter(DateTime.now().toUtc().add(const Duration(seconds: 30)))) {
      return current.tokens.accessToken;
    }
    return (await _refresh()).accessToken;
  }

  DateTime? get accessTokenExpiresAt => _session?.tokens.expiresAt;

  Future<MatchTicket> enqueue({
    required int boardSize,
    String mode = 'casual',
  }) async {
    final json = await _send('POST', '/matchmaking/tickets', body: {
      'mode': mode,
      'boardSize': boardSize,
      'ruleset': 'chinese',
      'region': 'local',
      'timeControl': {
        'kind': 'absolute',
        'mainSeconds': 600,
        'incrementSeconds': 0,
        'periodSeconds': 0,
        'periods': 0,
        'stonesPerPeriod': 0,
      },
    });
    return MatchTicket.fromJson(json);
  }

  Future<MatchTicket> getTicket(String id) async =>
      MatchTicket.fromJson(await _send('GET', '/matchmaking/tickets/$id'));

  Future<void> cancelTicket(String id) async {
    await _send('DELETE', '/matchmaking/tickets/$id');
  }

  Future<OnlineGameInfo> getGame(String id) async =>
      OnlineGameInfo.fromJson(await _send('GET', '/games/$id'));

  Future<String> getGameSgf(String id) async {
    final token = await accessToken();
    final response = await _http.get(
      _uri('/games/$id/sgf'),
      headers: {'Authorization': 'Bearer $token'},
    ).timeout(const Duration(seconds: 10));
    if (response.statusCode != 200) throw _exceptionFor(response);
    return response.body;
  }

  Future<Uri> webSocketUri() async {
    final token = await accessToken();
    final base = Uri.parse(_baseUrl);
    return base.replace(
      scheme: base.scheme == 'https' ? 'wss' : 'ws',
      path: '${base.path.replaceFirst(RegExp(r'/+$'), '')}/ws',
      queryParameters: {'token': token},
    );
  }

  OnlineApiClient testPeer() => OnlineApiClient(
        baseUrl: _baseUrl,
        storage: MemorySessionStorage(),
      );

  Future<TokenPair> _refresh() {
    final existing = _refreshInFlight;
    if (existing != null) return existing;
    final future = _performRefresh();
    _refreshInFlight = future;
    return future.whenComplete(() => _refreshInFlight = null);
  }

  Future<TokenPair> _performRefresh() async {
    final current = _session;
    if (current == null) {
      throw const ApiException('unauthenticated', 'Sign in to play online.');
    }
    final response = await _send(
      'POST',
      '/auth/refresh',
      body: {'refreshToken': current.tokens.refreshToken},
      authenticated: false,
    );
    final tokens = TokenPair.fromJson(_map(response['tokens']));
    await _saveSession(OnlineSession(user: current.user, tokens: tokens));
    return tokens;
  }

  Future<Map<String, dynamic>> _send(
    String method,
    String path, {
    Map<String, dynamic>? body,
    bool authenticated = true,
    bool retryAfterRefresh = true,
  }) async {
    final headers = <String, String>{'Accept': 'application/json'};
    if (body != null) headers['Content-Type'] = 'application/json';
    if (authenticated) {
      headers['Authorization'] = 'Bearer ${await accessToken()}';
    }
    final request = http.Request(method, _uri(path))..headers.addAll(headers);
    if (body != null) request.body = jsonEncode(body);
    final streamed =
        await _http.send(request).timeout(const Duration(seconds: 12));
    final response = await http.Response.fromStream(streamed);
    if (response.statusCode == 401 && authenticated && retryAfterRefresh) {
      await _refresh();
      return _send(method, path,
          body: body, authenticated: true, retryAfterRefresh: false);
    }
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw _exceptionFor(response);
    }
    if (response.statusCode == 204 || response.body.trim().isEmpty) {
      return const {};
    }
    final decoded = jsonDecode(response.body);
    if (decoded is! Map) {
      throw const ApiException(
          'invalid_response', 'The server returned an invalid response.');
    }
    return Map<String, dynamic>.from(decoded);
  }

  Uri _uri(String path) => Uri.parse('$_baseUrl$path');

  ApiException _exceptionFor(http.Response response) {
    try {
      final decoded = _map(jsonDecode(response.body));
      final error = _map(decoded['error']);
      return ApiException(
        error['code'] as String? ?? 'request_failed',
        error['message'] as String? ??
            'Request failed (${response.statusCode})',
        statusCode: response.statusCode,
      );
    } on Object {
      return ApiException(
        'request_failed',
        'Request failed (${response.statusCode})',
        statusCode: response.statusCode,
      );
    }
  }

  Future<void> _restoreSession() async {
    final raw = await _storage.read(_sessionKey);
    if (raw == null) return;
    try {
      final json = _map(jsonDecode(raw));
      _session = OnlineSession(
        user: OnlineUser.fromJson(_map(json['user'])),
        tokens: TokenPair.fromJson(_map(json['tokens'])),
      );
    } on Object {
      await clearSession();
    }
  }

  Future<void> _saveSession(OnlineSession session) async {
    _session = session;
    await _storage.write(
      _sessionKey,
      jsonEncode(
          {'user': session.user.toJson(), 'tokens': session.tokens.toJson()}),
    );
  }

  Future<void> clearSession() async {
    _session = null;
    await _storage.delete(_sessionKey);
  }

  void close() => _http.close();
}

Map<String, dynamic> _map(Object? value) {
  if (value is Map<String, dynamic>) return value;
  if (value is Map) return Map<String, dynamic>.from(value);
  throw const ApiException(
      'invalid_response', 'The server returned an invalid response.');
}
