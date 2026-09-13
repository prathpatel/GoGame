import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:go_game/src/online/api_client.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

void main() {
  test('guest auth persists and reuses a valid token', () async {
    var guestCalls = 0;
    final storage = MemorySessionStorage();
    final client = OnlineApiClient(
      baseUrl: 'http://example.test',
      storage: storage,
      httpClient: MockClient((request) async {
        expect(request.url.path, '/auth/guest');
        guestCalls++;
        return http.Response(
          jsonEncode({
            'user': {
              'id': 'user-1',
              'displayName': 'Prath',
              'rating': 1000,
              'isGuest': true,
            },
            'tokens': {
              'accessToken': 'access',
              'refreshToken': 'refresh',
              'expiresAt': DateTime.now()
                  .toUtc()
                  .add(const Duration(hours: 1))
                  .toIso8601String(),
            },
          }),
          201,
        );
      }),
    );

    final first = await client.ensureSession('Prath');
    final second = await client.ensureSession('Ignored');

    expect(first.user.displayName, 'Prath');
    expect(second.tokens.accessToken, 'access');
    expect(guestCalls, 1);
    client.close();
  });

  test('authenticated request refreshes an expired access token', () async {
    final storage = MemorySessionStorage();
    var refreshCalls = 0;
    final client = OnlineApiClient(
      baseUrl: 'http://example.test',
      storage: storage,
      httpClient: MockClient((request) async {
        if (request.url.path == '/auth/guest') {
          return http.Response(
            jsonEncode({
              'user': {
                'id': 'user-1',
                'displayName': 'Prath',
                'rating': 1000,
                'isGuest': true,
              },
              'tokens': {
                'accessToken': 'expired',
                'refreshToken': 'refresh-1',
                'expiresAt': DateTime.now()
                    .toUtc()
                    .subtract(const Duration(minutes: 1))
                    .toIso8601String(),
              },
            }),
            201,
          );
        }
        if (request.url.path == '/auth/refresh') {
          refreshCalls++;
          return http.Response(
            jsonEncode({
              'tokens': {
                'accessToken': 'fresh',
                'refreshToken': 'refresh-2',
                'expiresAt': DateTime.now()
                    .toUtc()
                    .add(const Duration(hours: 1))
                    .toIso8601String(),
              },
            }),
            200,
          );
        }
        expect(request.headers['authorization'], 'Bearer fresh');
        return http.Response(
            jsonEncode({'id': 'ticket-1', 'status': 'queued'}), 201);
      }),
    );

    await client.signInGuest('Prath');
    final ticket = await client.enqueue(boardSize: 9);

    expect(ticket.id, 'ticket-1');
    expect(refreshCalls, 1);
    client.close();
  });

  test('rejects invalid backend URLs', () async {
    final client = OnlineApiClient(
      baseUrl: 'http://example.test',
      storage: MemorySessionStorage(),
    );
    await expectLater(
      client.setBaseUrl('not a URL'),
      throwsA(isA<ApiException>()),
    );
    client.close();
  });
}
