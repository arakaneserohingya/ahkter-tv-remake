import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter/foundation.dart';
import 'package:flutter/painting.dart';
import 'package:http/http.dart' as http;
import 'package:path_provider/path_provider.dart';

/// Channel logos cached on disk, so they are downloaded once instead of on every launch,
/// plus the remembered "light logo, needs a dark card" result for each one.
class LogoCache {
  static Directory? _dir;
  static final Map<String, bool> tones = {};
  static Timer? _saveTimer;

  static Future<void> init() async {
    try {
      final base = await getApplicationSupportDirectory();
      _dir = Directory('${base.path}/logos')..createSync(recursive: true);
      final f = File('${_dir!.path}/tones.json');
      if (f.existsSync()) {
        (jsonDecode(f.readAsStringSync()) as Map).forEach((k, v) => tones[k as String] = v as bool);
      }
    } catch (_) {
      _dir = null; // fall back to network-only
    }
  }

  static void rememberTone(String url, bool dark) {
    tones[url] = dark;
    _saveTimer?.cancel();
    _saveTimer = Timer(const Duration(seconds: 3), () {
      final d = _dir;
      if (d != null) File('${d.path}/tones.json').writeAsString(jsonEncode(tones)).ignore();
    });
  }

  static File? _file(String url) {
    final d = _dir;
    if (d == null) return null;
    // FNV-1a 64-bit of the URL as a file name
    var h = 0xcbf29ce484222325;
    for (final c in url.codeUnits) {
      h = (h ^ c) * 0x100000001b3;
    }
    return File('${d.path}/${h.toUnsigned(64).toRadixString(16)}');
  }
}

/// Like NetworkImage, but reads from / writes to [LogoCache] on disk.
@immutable
class CachedLogoImage extends ImageProvider<CachedLogoImage> {
  final String url;

  const CachedLogoImage(this.url);

  @override
  Future<CachedLogoImage> obtainKey(ImageConfiguration configuration) => SynchronousFuture(this);

  @override
  ImageStreamCompleter loadImage(CachedLogoImage key, ImageDecoderCallback decode) =>
      MultiFrameImageStreamCompleter(codec: _load(decode), scale: 1.0, debugLabel: url);

  Future<ui.Codec> _load(ImageDecoderCallback decode) async {
    final file = LogoCache._file(url);
    Uint8List? bytes;
    if (file != null && await file.exists()) {
      bytes = await file.readAsBytes();
    }
    if (bytes == null || bytes.isEmpty) {
      final r = await http.get(Uri.parse(url)).timeout(const Duration(seconds: 15));
      if (r.statusCode != 200 || r.bodyBytes.isEmpty) {
        throw StateError('logo unavailable: $url');
      }
      bytes = r.bodyBytes;
      file?.writeAsBytes(bytes).ignore();
    }
    return decode(await ui.ImmutableBuffer.fromUint8List(bytes));
  }

  @override
  bool operator ==(Object other) => other is CachedLogoImage && other.url == url;

  @override
  int get hashCode => url.hashCode;
}
