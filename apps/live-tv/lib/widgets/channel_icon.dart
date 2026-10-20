import 'dart:ui' as ui;

import 'package:flutter/material.dart';

import '../model/channel.dart';
import 'logo_cache.dart';

/// Channel artwork: the channel's logo when it has one, otherwise a generated icon
/// (colour derived from the name + initials) so every channel is recognisable.
class ChannelIcon extends StatelessWidget {
  final Channel channel;
  final double width;
  final double height;

  const ChannelIcon({super.key, required this.channel, this.width = 104, this.height = 60});

  @override
  Widget build(BuildContext context) {
    final generated = GeneratedChannelIcon(name: channel.name, width: width, height: height);
    if (!channel.hasLogo) return generated;
    return _LogoCard(channel: channel, width: width, height: height, fallback: generated);
  }
}

/// Shows a logo on a card whose colour suits it: white for dark logos,
/// near-black for light logos drawn on transparency (they would vanish on white).
class _LogoCard extends StatefulWidget {
  final Channel channel;
  final double width;
  final double height;
  final Widget fallback;

  const _LogoCard({required this.channel, required this.width, required this.height, required this.fallback});

  @override
  State<_LogoCard> createState() => _LogoCardState();
}

class _LogoCardState extends State<_LogoCard> {
  /// url -> true when the logo needs a dark card (remembered on disk between launches)
  static Map<String, bool> get _darkCache => LogoCache.tones;
  static final Set<String> _failed = {};

  ImageProvider? _provider;
  ImageStream? _stream;
  late final ImageStreamListener _listener = ImageStreamListener(_onImage, onError: _onError);
  bool _loaded = false;
  bool _attaching = false;

  String get _url => widget.channel.logoUrl;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_provider != null) return;
    final dpr = MediaQuery.devicePixelRatioOf(context);
    // decode small: the TV has 1 GB of RAM and lists can hold 1000+ channels
    _provider = ResizeImage(CachedLogoImage(_url), width: (widget.width * 0.84 * dpr).round(), allowUpscaling: false);
    _stream = _provider!.resolve(createLocalImageConfiguration(context));
    // a logo already in memory reports synchronously here: show it in this same build
    _attaching = true;
    _stream!.addListener(_listener);
    _attaching = false;
  }

  @override
  void dispose() {
    _stream?.removeListener(_listener);
    super.dispose();
  }

  void _onError(Object error, StackTrace? stack) {
    _failed.add(_url);
    if (mounted) setState(() {});
  }

  Future<void> _onImage(ImageInfo info, bool sync) async {
    if (_attaching && _darkCache.containsKey(_url)) {
      _loaded = true;   // no setState needed: we're already building
      return;
    }
    if (!_darkCache.containsKey(_url)) {
      LogoCache.rememberTone(_url, await _needsDarkCard(info.image));
    }
    if (mounted) setState(() => _loaded = true);
  }

  static Future<bool> _needsDarkCard(ui.Image image) async {
    final data = await image.toByteData(format: ui.ImageByteFormat.rawRgba);
    if (data == null) return false;
    final px = data.lengthInBytes ~/ 4;
    final step = px > 4000 ? px ~/ 4000 : 1;   // sample ~4000 pixels
    var opaque = 0, transparent = 0;
    var lum = 0.0;
    for (var i = 0; i < px; i += step) {
      final o = i * 4;
      final a = data.getUint8(o + 3);
      if (a < 40) {
        transparent++;
        continue;
      }
      opaque++;
      lum += 0.2126 * data.getUint8(o) + 0.7152 * data.getUint8(o + 1) + 0.0722 * data.getUint8(o + 2);
    }
    if (opaque == 0 || transparent == 0) return false;   // opaque logos bring their own background
    return lum / opaque / 255 > 0.6;
  }

  @override
  Widget build(BuildContext context) {
    if (_failed.contains(_url)) return widget.fallback;
    final dark = _darkCache[_url] ?? false;
    return Container(
      width: widget.width,
      height: widget.height,
      padding: EdgeInsets.symmetric(horizontal: widget.width * 0.08, vertical: widget.height * 0.1),
      decoration: BoxDecoration(
        color: dark ? const Color(0xFF2A2A33) : const Color(0xFFF4F4F6),
        borderRadius: BorderRadius.circular(10),
      ),
      child: _loaded
          ? Image(image: _provider!, fit: BoxFit.contain, gaplessPlayback: true)
          // while the logo downloads: just the empty card (cheap to build)
          : null,
    );
  }
}

class GeneratedChannelIcon extends StatelessWidget {
  final String name;
  final double width;
  final double height;

  const GeneratedChannelIcon({super.key, required this.name, required this.width, required this.height});

  @override
  Widget build(BuildContext context) {
    final base = _colorFor(name);
    final hsl = HSLColor.fromColor(base);
    final dark = hsl.withLightness((hsl.lightness - 0.16).clamp(0.0, 1.0)).toColor();
    final initials = _initials(name);
    return Container(
      width: width,
      height: height,
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(10),
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [base, dark],
        ),
      ),
      child: Stack(
        children: [
          Positioned(
            right: height * 0.07,
            top: height * 0.07,
            child: Icon(Icons.live_tv_rounded, size: height * 0.18, color: Colors.white.withValues(alpha: 0.55)),
          ),
          Center(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 10),
              child: FittedBox(
                fit: BoxFit.scaleDown,
                child: Text(
                  initials,
                  style: TextStyle(
                    color: Colors.white,
                    fontSize: height * (initials.length > 3 ? 0.3 : 0.36),
                    fontWeight: FontWeight.w800,
                    letterSpacing: 1,
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// "Aaj Tak HD (1080p)" -> "AT", "9X Jalwa" -> "9X", "Aastha Bhajan" -> "AB", "Zoom" -> "ZOO"
String _initials(String name) {
  final clean = name
      .replaceAll(RegExp(r'\(.*?\)|\[.*?\]'), '')
      .replaceAll(RegExp(r'\b(HD|SD|TV)\b', caseSensitive: false), '')
      .trim();
  final words = clean.split(RegExp(r'[\s\-_/&.]+')).where((w) => w.isNotEmpty).toList();
  if (words.isEmpty) return 'TV';
  final first = words.first;
  if (RegExp(r'\d').hasMatch(first) && first.length <= 4) return first.toUpperCase();
  if (words.length == 1) return first.substring(0, first.length < 3 ? first.length : 3).toUpperCase();
  return words.take(3).map((w) => w[0]).join().toUpperCase();
}

/// Stable, pleasant colour per channel name (same channel -> same colour every time).
Color _colorFor(String name) {
  final key = name.replaceAll(RegExp(r'\(.*?\)|\[.*?\]'), '').trim().toLowerCase();
  var h = 0;
  for (final c in key.codeUnits) {
    h = (h * 31 + c) & 0x7fffffff;
  }
  return HSLColor.fromAHSL(1, (h % 360).toDouble(), 0.55, 0.45).toColor();
}
