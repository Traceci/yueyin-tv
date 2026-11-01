/*
 * 自定义音源运行时宿主（LX Music 兼容层）
 *
 * 音源脚本是「用户自己导入的 JS」，这里只负责给它一个和 LX Music 桌面端等价的
 * 最小 API 面：lx.request / lx.utils.crypto / lx.utils.buffer / lx.send / lx.on。
 * 网络请求一律经原生桥（OkHttp）发出，绕开 WebView 的跨域限制。
 *
 * 本文件不内置任何音源，不含任何平台密钥。
 */
(function () {
  'use strict';

  /* ================= 字节工具 ================= */
  var B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  var B64R = (function () {
    var m = {};
    for (var i = 0; i < B64.length; i++) m[B64.charAt(i)] = i;
    m['-'] = 62; m['_'] = 63;
    return m;
  })();

  function bytesFromUtf8(str) {
    if (typeof TextEncoder !== 'undefined') return new TextEncoder().encode(str);
    var out = [];
    for (var i = 0; i < str.length; i++) {
      var c = str.charCodeAt(i);
      if (c < 0x80) out.push(c);
      else if (c < 0x800) { out.push(0xc0 | (c >> 6), 0x80 | (c & 63)); }
      else if (c >= 0xd800 && c <= 0xdbff) {
        var c2 = str.charCodeAt(++i);
        var cp = 0x10000 + ((c - 0xd800) << 10) + (c2 - 0xdc00);
        out.push(0xf0 | (cp >> 18), 0x80 | ((cp >> 12) & 63), 0x80 | ((cp >> 6) & 63), 0x80 | (cp & 63));
      } else { out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63)); }
    }
    return new Uint8Array(out);
  }

  function utf8FromBytes(b) {
    if (typeof TextDecoder !== 'undefined') return new TextDecoder('utf-8').decode(b);
    var out = '', i = 0;
    while (i < b.length) {
      var c = b[i++];
      if (c < 0x80) out += String.fromCharCode(c);
      else if (c < 0xe0) out += String.fromCharCode(((c & 31) << 6) | (b[i++] & 63));
      else if (c < 0xf0) out += String.fromCharCode(((c & 15) << 12) | ((b[i++] & 63) << 6) | (b[i++] & 63));
      else {
        var cp = ((c & 7) << 18) | ((b[i++] & 63) << 12) | ((b[i++] & 63) << 6) | (b[i++] & 63);
        cp -= 0x10000;
        out += String.fromCharCode(0xd800 + (cp >> 10), 0xdc00 + (cp & 1023));
      }
    }
    return out;
  }

  function b64FromBytes(b) {
    var out = '', i = 0;
    for (; i + 2 < b.length; i += 3) {
      var n = (b[i] << 16) | (b[i + 1] << 8) | b[i + 2];
      out += B64[(n >> 18) & 63] + B64[(n >> 12) & 63] + B64[(n >> 6) & 63] + B64[n & 63];
    }
    var rest = b.length - i;
    if (rest === 1) {
      var n1 = b[i] << 16;
      out += B64[(n1 >> 18) & 63] + B64[(n1 >> 12) & 63] + '==';
    } else if (rest === 2) {
      var n2 = (b[i] << 16) | (b[i + 1] << 8);
      out += B64[(n2 >> 18) & 63] + B64[(n2 >> 12) & 63] + B64[(n2 >> 6) & 63] + '=';
    }
    return out;
  }

  function bytesFromB64(s) {
    s = String(s).replace(/[\r\n\s]/g, '');
    var pad = s.indexOf('=');
    if (pad >= 0) s = s.slice(0, pad);
    var out = new Uint8Array(Math.floor((s.length * 3) / 4));
    var p = 0, buf = 0, bits = 0;
    for (var i = 0; i < s.length; i++) {
      var v = B64R[s.charAt(i)];
      if (v === undefined) continue;
      buf = (buf << 6) | v; bits += 6;
      if (bits >= 8) { bits -= 8; out[p++] = (buf >> bits) & 0xff; }
    }
    return out.subarray(0, p);
  }

  function bytesFromHex(s) {
    s = String(s).replace(/[^0-9a-fA-F]/g, '');
    if (s.length % 2) s = '0' + s;
    var out = new Uint8Array(s.length / 2);
    for (var i = 0; i < out.length; i++) out[i] = parseInt(s.substr(i * 2, 2), 16);
    return out;
  }

  function hexFromBytes(b) {
    var s = '';
    for (var i = 0; i < b.length; i++) s += (b[i] < 16 ? '0' : '') + b[i].toString(16);
    return s;
  }

  /* ================= Buffer polyfill ================= */
  function Buffer(arg, enc) {
    if (typeof arg === 'number') {
      var b = new Uint8Array(arg);
      return makeBuf(b, 0, b.length);
    }
    return bufferFrom(arg, enc);
  }

  function makeBuf(u8, off, len) {
    var o = Object.create(BufferProto);
    o._u8 = u8; o._off = off || 0; o._len = len === undefined ? u8.length : len;
    return o;
  }

  var BufferProto = {
    get length() { return this._len; },
    toString: function (enc, start, end) {
      var s = start === undefined ? 0 : start;
      var e = end === undefined ? this._len : end;
      var view = this._u8.subarray(this._off + s, this._off + e);
      enc = (enc || 'utf8').toLowerCase();
      if (enc === 'hex') return hexFromBytes(view);
      if (enc === 'base64') return b64FromBytes(view);
      if (enc === 'latin1' || enc === 'binary') {
        var o = '';
        for (var i = 0; i < view.length; i++) o += String.fromCharCode(view[i]);
        return o;
      }
      return utf8FromBytes(view);
    },
    toJSON: function () { return { type: 'Buffer', data: Array.prototype.slice.call(this.bytes()) }; },
    slice: function (s, e) {
      var len = this._len;
      s = s === undefined ? 0 : (s < 0 ? Math.max(len + s, 0) : Math.min(s, len));
      e = e === undefined ? len : (e < 0 ? Math.max(len + e, 0) : Math.min(e, len));
      var n = Math.max(e - s, 0);
      return makeBuf(this._u8, this._off + s, n);
    },
    subarray: function (s, e) { return this.slice(s, e); },
    bytes: function () {
      return this._u8.subarray(this._off, this._off + this._len);
    },
    readUInt8: function (i) { return this._u8[this._off + i]; },
    readUInt16BE: function (i) {
      return (this._u8[this._off + i] << 8) | this._u8[this._off + i + 1];
    },
    readUInt32BE: function (i) {
      var u = this._u8, o = this._off + i;
      return ((u[o] << 24) | (u[o + 1] << 16) | (u[o + 2] << 8) | u[o + 3]) >>> 0;
    },
    writeUInt32BE: function (v, i) {
      var u = this._u8, o = this._off + i;
      u[o] = (v >>> 24) & 0xff; u[o + 1] = (v >>> 16) & 0xff;
      u[o + 2] = (v >>> 8) & 0xff; u[o + 3] = v & 0xff;
      return i + 4;
    },
    equals: function (o) {
      if (!o || o.length !== this._len) return false;
      var a = this.bytes(), b = o.bytes ? o.bytes() : o;
      for (var i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
      return true;
    },
  };

  function bufferFrom(v, enc) {
    if (v && v._u8) return makeBuf(v._u8, v._off, v._len);
    if (typeof v === 'string') {
      enc = (enc || 'utf8').toLowerCase();
      if (enc === 'hex') return makeBuf(bytesFromHex(v));
      if (enc === 'base64') return makeBuf(bytesFromB64(v));
      if (enc === 'latin1' || enc === 'binary') {
        var a = new Uint8Array(v.length);
        for (var i = 0; i < v.length; i++) a[i] = v.charCodeAt(i) & 0xff;
        return makeBuf(a);
      }
      return makeBuf(bytesFromUtf8(v));
    }
    if (v instanceof ArrayBuffer) return makeBuf(new Uint8Array(v));
    if (v && v.buffer instanceof ArrayBuffer && typeof v.byteLength === 'number') {
      return makeBuf(new Uint8Array(v.buffer, v.byteOffset || 0, v.byteLength));
    }
    if (Object.prototype.toString.call(v) === '[object Array]' || v instanceof Uint8Array || v instanceof Int8Array) {
      return makeBuf(new Uint8Array(v));
    }
    if (v === undefined || v === null) return makeBuf(new Uint8Array(0));
    if (typeof v === 'number') return makeBuf(new Uint8Array(v));
    return makeBuf(bytesFromUtf8(String(v)));
  }

  function toBytes(v, enc) {
    if (v && v._u8) return v.bytes();
    if (v instanceof Uint8Array) return v;
    if (v instanceof ArrayBuffer) return new Uint8Array(v);
    if (Object.prototype.toString.call(v) === '[object Array]') return new Uint8Array(v);
    if (typeof v === 'string') return enc === 'hex' ? bytesFromHex(v) : bytesFromUtf8(v);
    return new Uint8Array(0);
  }

  Buffer.from = bufferFrom;
  Buffer.alloc = function (n, fill) { var a = new Uint8Array(n); if (fill) a.fill(fill); return makeBuf(a); };
  Buffer.concat = function (list, total) {
    var n = total;
    if (n === undefined) { n = 0; for (var i = 0; i < list.length; i++) n += list[i].length; }
    var out = new Uint8Array(n), p = 0;
    for (var j = 0; j < list.length; j++) {
      var b = toBytes(list[j]);
      out.set(b.subarray(0, Math.min(b.length, n - p)), p);
      p += b.length;
      if (p >= n) break;
    }
    return makeBuf(out);
  };
  Buffer.isBuffer = function (v) { return !!(v && v._u8); };

  /* ================= MD5 ================= */
  function safeAdd(x, y) {
    var lsw = (x & 0xffff) + (y & 0xffff);
    var msw = (x >> 16) + (y >> 16) + (lsw >> 16);
    return (msw << 16) | (lsw & 0xffff);
  }
  function rol(num, cnt) { return (num << cnt) | (num >>> (32 - cnt)); }
  function md5cmn(q, a, b, x, s, t) { return safeAdd(rol(safeAdd(safeAdd(a, q), safeAdd(x, t)), s), b); }
  function md5ff(a, b, c, d, x, s, t) { return md5cmn((b & c) | (~b & d), a, b, x, s, t); }
  function md5gg(a, b, c, d, x, s, t) { return md5cmn((b & d) | (c & ~d), a, b, x, s, t); }
  function md5hh(a, b, c, d, x, s, t) { return md5cmn(b ^ c ^ d, a, b, x, s, t); }
  function md5ii(a, b, c, d, x, s, t) { return md5cmn(c ^ (b | ~d), a, b, x, s, t); }

  function md5Cycle(state, k) {
    var a = state[0], b = state[1], c = state[2], d = state[3];
    a = md5ff(a, b, c, d, k[0], 7, -680876936);
    d = md5ff(d, a, b, c, k[1], 12, -389564586);
    c = md5ff(c, d, a, b, k[2], 17, 606105819);
    b = md5ff(b, c, d, a, k[3], 22, -1044525330);
    a = md5ff(a, b, c, d, k[4], 7, -176418897);
    d = md5ff(d, a, b, c, k[5], 12, 1200080426);
    c = md5ff(c, d, a, b, k[6], 17, -1473231341);
    b = md5ff(b, c, d, a, k[7], 22, -45705983);
    a = md5ff(a, b, c, d, k[8], 7, 1770035416);
    d = md5ff(d, a, b, c, k[9], 12, -1958414417);
    c = md5ff(c, d, a, b, k[10], 17, -42063);
    b = md5ff(b, c, d, a, k[11], 22, -1990404162);
    a = md5ff(a, b, c, d, k[12], 7, 1804603682);
    d = md5ff(d, a, b, c, k[13], 12, -40341101);
    c = md5ff(c, d, a, b, k[14], 17, -1502002290);
    b = md5ff(b, c, d, a, k[15], 22, 1236535329);

    a = md5gg(a, b, c, d, k[1], 5, -165796510);
    d = md5gg(d, a, b, c, k[6], 9, -1069501632);
    c = md5gg(c, d, a, b, k[11], 14, 643717713);
    b = md5gg(b, c, d, a, k[0], 20, -373897302);
    a = md5gg(a, b, c, d, k[5], 5, -701558691);
    d = md5gg(d, a, b, c, k[10], 9, 38016083);
    c = md5gg(c, d, a, b, k[15], 14, -660478335);
    b = md5gg(b, c, d, a, k[4], 20, -405537848);
    a = md5gg(a, b, c, d, k[9], 5, 568446438);
    d = md5gg(d, a, b, c, k[14], 9, -1019803690);
    c = md5gg(c, d, a, b, k[3], 14, -187363961);
    b = md5gg(b, c, d, a, k[8], 20, 1163531501);
    a = md5gg(a, b, c, d, k[13], 5, -1444681467);
    d = md5gg(d, a, b, c, k[2], 9, -51403784);
    c = md5gg(c, d, a, b, k[7], 14, 1735328473);
    b = md5gg(b, c, d, a, k[12], 20, -1926607734);

    a = md5hh(a, b, c, d, k[5], 4, -378558);
    d = md5hh(d, a, b, c, k[8], 11, -2022574463);
    c = md5hh(c, d, a, b, k[11], 16, 1839030562);
    b = md5hh(b, c, d, a, k[14], 23, -35309556);
    a = md5hh(a, b, c, d, k[1], 4, -1530992060);
    d = md5hh(d, a, b, c, k[4], 11, 1272893353);
    c = md5hh(c, d, a, b, k[7], 16, -155497632);
    b = md5hh(b, c, d, a, k[10], 23, -1094730640);
    a = md5hh(a, b, c, d, k[13], 4, 681279174);
    d = md5hh(d, a, b, c, k[0], 11, -358537222);
    c = md5hh(c, d, a, b, k[3], 16, -722521979);
    b = md5hh(b, c, d, a, k[6], 23, 76029189);
    a = md5hh(a, b, c, d, k[9], 4, -640364487);
    d = md5hh(d, a, b, c, k[12], 11, -421815835);
    c = md5hh(c, d, a, b, k[15], 16, 530742520);
    b = md5hh(b, c, d, a, k[2], 23, -995338651);

    a = md5ii(a, b, c, d, k[0], 6, -198630844);
    d = md5ii(d, a, b, c, k[7], 10, 1126891415);
    c = md5ii(c, d, a, b, k[14], 15, -1416354905);
    b = md5ii(b, c, d, a, k[5], 21, -57434055);
    a = md5ii(a, b, c, d, k[12], 6, 1700485571);
    d = md5ii(d, a, b, c, k[3], 10, -1894986606);
    c = md5ii(c, d, a, b, k[10], 15, -1051523);
    b = md5ii(b, c, d, a, k[1], 21, -2054922799);
    a = md5ii(a, b, c, d, k[8], 6, 1873313359);
    d = md5ii(d, a, b, c, k[15], 10, -30611744);
    c = md5ii(c, d, a, b, k[6], 15, -1560198380);
    b = md5ii(b, c, d, a, k[13], 21, 1309151649);
    a = md5ii(a, b, c, d, k[4], 6, -145523070);
    d = md5ii(d, a, b, c, k[11], 10, -1120210379);
    c = md5ii(c, d, a, b, k[2], 15, 718787259);
    b = md5ii(b, c, d, a, k[9], 21, -343485551);

    state[0] = safeAdd(a, state[0]);
    state[1] = safeAdd(b, state[1]);
    state[2] = safeAdd(c, state[2]);
    state[3] = safeAdd(d, state[3]);
  }

  function md5(input) {
    var bytes = toBytes(input);
    var n = bytes.length;
    var tail = n % 64;
    var padded = new Uint8Array(n + (tail < 56 ? 64 - tail : 128 - tail));
    padded.set(bytes);
    padded[n] = 0x80;
    var bitLen = n * 8;
    // 长度按小端 64 位写在末尾
    var end = padded.length;
    padded[end - 8] = bitLen & 0xff;
    padded[end - 7] = (bitLen >>> 8) & 0xff;
    padded[end - 6] = (bitLen >>> 16) & 0xff;
    padded[end - 5] = (bitLen >>> 24) & 0xff;
    var hi = Math.floor(n / 0x20000000);
    padded[end - 4] = hi & 0xff;

    var state = [1732584193, -271733879, -1732584194, 271733878];
    var k = new Array(16);
    for (var off = 0; off < padded.length; off += 64) {
      for (var i = 0; i < 16; i++) {
        var j = off + i * 4;
        k[i] = padded[j] | (padded[j + 1] << 8) | (padded[j + 2] << 16) | (padded[j + 3] << 24);
      }
      md5Cycle(state, k);
    }
    var out = new Uint8Array(16);
    for (var s = 0; s < 4; s++) {
      var v = state[s];
      out[s * 4] = v & 0xff;
      out[s * 4 + 1] = (v >>> 8) & 0xff;
      out[s * 4 + 2] = (v >>> 16) & 0xff;
      out[s * 4 + 3] = (v >>> 24) & 0xff;
    }
    return out;
  }

  /* ================= AES ================= */
  var SBOX = new Uint8Array([
    0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
    0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
    0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
    0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
    0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
    0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
    0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
    0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
    0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
    0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
    0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
    0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
    0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
    0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
    0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
    0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16,
  ]);

  var RCON = [0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1b, 0x36];

  function xtime(a) { return ((a << 1) ^ ((a & 0x80) ? 0x1b : 0)) & 0xff; }

  function expandKey(key) {
    var nk = key.length / 4;
    var nr = nk + 6;
    var w = new Uint8Array(16 * (nr + 1));
    w.set(key);
    var total = 4 * (nr + 1);
    for (var i = nk; i < total; i++) {
      var t = [w[(i - 1) * 4], w[(i - 1) * 4 + 1], w[(i - 1) * 4 + 2], w[(i - 1) * 4 + 3]];
      if (i % nk === 0) {
        t = [SBOX[t[1]] ^ RCON[i / nk - 1], SBOX[t[2]], SBOX[t[3]], SBOX[t[0]]];
      } else if (nk > 6 && i % nk === 4) {
        t = [SBOX[t[0]], SBOX[t[1]], SBOX[t[2]], SBOX[t[3]]];
      }
      for (var j = 0; j < 4; j++) {
        w[i * 4 + j] = w[(i - nk) * 4 + j] ^ t[j];
      }
    }
    return { w: w, nr: nr };
  }

  function encryptBlock(block, ks) {
    var s = new Uint8Array(16);
    s.set(block);
    var w = ks.w, nr = ks.nr;
    addKey(s, w, 0);
    for (var r = 1; r <= nr; r++) {
      for (var i = 0; i < 16; i++) s[i] = SBOX[s[i]];
      shiftRows(s);
      if (r !== nr) mixCols(s);
      addKey(s, w, r);
    }
    return s;
  }

  function addKey(s, w, round) {
    for (var i = 0; i < 16; i++) s[i] ^= w[round * 16 + i];
  }

  // 状态按列存放：s[r + 4c]
  function shiftRows(s) {
    var t = s.slice();
    for (var r = 1; r < 4; r++) {
      for (var c = 0; c < 4; c++) {
        s[r + 4 * c] = t[r + 4 * ((c + r) % 4)];
      }
    }
  }

  function mixCols(s) {
    for (var c = 0; c < 4; c++) {
      var i = c * 4;
      var a0 = s[i], a1 = s[i + 1], a2 = s[i + 2], a3 = s[i + 3];
      s[i] = xtime(a0) ^ (xtime(a1) ^ a1) ^ a2 ^ a3;
      s[i + 1] = a0 ^ xtime(a1) ^ (xtime(a2) ^ a2) ^ a3;
      s[i + 2] = a0 ^ a1 ^ xtime(a2) ^ (xtime(a3) ^ a3);
      s[i + 3] = (xtime(a0) ^ a0) ^ a1 ^ a2 ^ xtime(a3);
    }
  }

  function pkcs7(data) {
    var pad = 16 - (data.length % 16);
    var out = new Uint8Array(data.length + pad);
    out.set(data);
    out.fill(pad, data.length);
    return out;
  }

  /** LX 的 aesEncrypt：mode 形如 aes-128-cbc / aes-256-ecb，返回 base64 */
  function aesEncrypt(data, mode, key, iv) {
    var bytes = toBytes(data);
    var keyBytes = toBytes(key);
    var m = String(mode || 'aes-128-cbc').toLowerCase();
    var isEcb = m.indexOf('ecb') >= 0;
    if (keyBytes.length !== 16 && keyBytes.length !== 24 && keyBytes.length !== 32) {
      throw new Error('AES key must be 16/24/32 bytes, got ' + keyBytes.length);
    }
    var buf = pkcs7(bytes);
    var ks = expandKey(keyBytes);
    var prev = isEcb ? null : toBytes(iv);
    if (!isEcb && prev.length !== 16) throw new Error('AES iv must be 16 bytes, got ' + prev.length);
    var out = new Uint8Array(buf.length);
    for (var off = 0; off < buf.length; off += 16) {
      var blk = buf.subarray(off, off + 16);
      if (!isEcb) {
        var x = new Uint8Array(16);
        for (var i = 0; i < 16; i++) x[i] = blk[i] ^ prev[i];
        blk = x;
      }
      var enc = encryptBlock(blk, ks);
      out.set(enc, off);
      prev = enc;
    }
    return b64FromBytes(out);
  }

  /* ================= RSA (PKCS#1 v1.5) ================= */
  function b64ToBigInt(s) {
    var bytes = bytesFromB64(s);
    var hex = '0x' + (hexFromBytes(bytes) || '0');
    return BigInt(hex);
  }

  function derReadLength(u8, p) {
    var len = u8[p++];
    if (len & 0x80) {
      var n = len & 0x7f;
      len = 0;
      for (var i = 0; i < n; i++) len = (len << 8) | u8[p++];
    }
    return { len: len, next: p };
  }

  function parsePublicKey(pem) {
    var body = String(pem)
      .replace(/-----BEGIN[^-]+-----/g, '')
      .replace(/-----END[^-]+-----/g, '')
      .replace(/[\r\n\s]/g, '');
    var der = bytesFromB64(body);
    var p = 0;
    if (der[p++] !== 0x30) throw new Error('bad public key');
    var seq = derReadLength(der, p); p = seq.next;
    if (der[p++] !== 0x02) throw new Error('bad modulus');
    var nl = derReadLength(der, p); p = nl.next;
    var nBytes = der.subarray(p, p + nl.len); p += nl.len;
    if (der[p++] !== 0x02) throw new Error('bad exponent');
    var el = derReadLength(der, p); p = el.next;
    var eBytes = der.subarray(p, p + el.len);
    return {
      n: BigInt('0x' + (hexFromBytes(nBytes) || '0')),
      e: BigInt('0x' + (hexFromBytes(eBytes) || '0')),
      size: nBytes.length,
    };
  }

  function bigIntToBytes(v, size) {
    var hex = v.toString(16);
    if (hex.length % 2) hex = '0' + hex;
    var raw = bytesFromHex(hex);
    var out = new Uint8Array(size);
    out.set(raw.subarray(Math.max(0, raw.length - size)), Math.max(0, size - raw.length));
    return out;
  }

  function modPow(base, exp, mod) {
    var result = BigInt(1);
    var b = base % mod;
    var e = exp;
    while (e > BigInt(0)) {
      if (e & BigInt(1)) result = (result * b) % mod;
      b = (b * b) % mod;
      e = e >> BigInt(1);
    }
    return result;
  }

  function rsaEncrypt(data, key) {
    var info = parsePublicKey(key);
    var msg = toBytes(data);
    var k = info.size;
    if (msg.length > k - 11) throw new Error('RSA message too long');
    var em = new Uint8Array(k);
    em[0] = 0x00;
    em[1] = 0x02;
    var ps = k - 3 - msg.length;
    var rand = new Uint8Array(ps);
    if (typeof crypto !== 'undefined' && crypto.getRandomValues) crypto.getRandomValues(rand);
    else for (var r = 0; r < ps; r++) rand[r] = 1 + Math.floor(Math.random() * 255);
    for (var i = 0; i < ps; i++) em[2 + i] = rand[i] === 0 ? 1 : rand[i];
    em[2 + ps] = 0x00;
    em.set(msg, 3 + ps);
    var m = BigInt('0x' + hexFromBytes(em));
    var c = modPow(m, info.e, info.n);
    return b64FromBytes(bigIntToBytes(c, k));
  }

  /* ================= 原生桥 ================= */
  var handlers = {};
  var sent = [];
  var pending = {};
  var seq = 0;
  var bridge = window.YueYinBridge || null;
  var logBuf = [];

  function log(msg) {
    var line = String(msg);
    logBuf.push(line);
    if (logBuf.length > 200) logBuf.shift();
    try { if (bridge) bridge.nativeLog(line); } catch (e) { /* ignore */ }
  }

  function send(name, data) {
    if (name === 'inited' || name === 'updateAlert') {
      // 这两个事件是给宿主的元信息，原样转给 Kotlin
      try { if (bridge) bridge.nativeEvent(name, JSON.stringify(data || {})); } catch (e) { /* ignore */ }
    }
    sent.push({ name: name, data: data });
    return Promise.resolve();
  }

  function request(url, options, callback) {
    var opts = (options && typeof options === 'object') ? options : {};
    var cb = typeof options === 'function' ? options : callback;
    var method = String(opts.method || 'GET').toUpperCase();
    var body = opts.body;
    var bodyB64 = '';
    if (body !== undefined && body !== null) {
      if (typeof body === 'string') bodyB64 = b64FromBytes(bytesFromUtf8(body));
      else if (body._u8) bodyB64 = b64FromBytes(body.bytes());
      else if (body instanceof ArrayBuffer) bodyB64 = b64FromBytes(new Uint8Array(body));
      else if (typeof body === 'object') {
        var s = JSON.stringify(body);
        bodyB64 = b64FromBytes(bytesFromUtf8(s));
        opts.headers = opts.headers || {};
        if (!hasHeader(opts.headers, 'content-type')) opts.headers['Content-Type'] = 'application/json';
      } else bodyB64 = b64FromBytes(bytesFromUtf8(String(body)));
    }
    var id = String(++seq);
    var headers = {};
    if (opts.headers) {
      for (var k in opts.headers) {
        if (Object.prototype.hasOwnProperty.call(opts.headers, k)) headers[k] = String(opts.headers[k]);
      }
    }
    var timeout = opts.timeout ? Number(opts.timeout) : 0;
    return new Promise(function (resolve) {
      pending[id] = {
        cb: cb,
        done: function (err, resp, text, rawB64) {
          var raw = bytesFromB64(rawB64 || '');
          var bodyText = text;
          if (bodyText === undefined || bodyText === null) bodyText = utf8FromBytes(raw);
          var r = {
            statusCode: resp ? resp.statusCode : 0,
            headers: (resp && resp.headers) || {},
            body: bodyText,
            raw: raw.buffer,
            bytes: raw.length,
          };
          if (cb) { try { cb(err, r, bodyText); } catch (e) { log('callback error: ' + e.message); } }
          resolve(err ? null : bodyText);
        },
      };
      try {
        if (!bridge) throw new Error('bridge unavailable');
        bridge.nativeRequest(id, String(url), method, JSON.stringify(headers), bodyB64, timeout);
      } catch (e) {
        delete pending[id];
        var err = new Error('request failed: ' + (e && e.message));
        if (cb) cb(err, { statusCode: 0, headers: {}, body: '' }, '');
        resolve(null);
      }
    });
  }

  function hasHeader(h, name) {
    for (var k in h) if (k.toLowerCase() === name) return true;
    return false;
  }

  // 原生回调
  window.__lxResponse = function (id, statusCode, headersJson, bodyB64) {
    var p = pending[id];
    if (!p) return;
    delete pending[id];
    var headers = {};
    try { headers = JSON.parse(headersJson || '{}'); } catch (e) { /* ignore */ }
    var raw = bytesFromB64(bodyB64 || '');
    p.done(null, { statusCode: statusCode | 0, headers: headers }, utf8FromBytes(raw), bodyB64 || '');
  };

  window.__lxResponseError = function (id, message, statusCode) {
    var p = pending[id];
    if (!p) return;
    delete pending[id];
    var err = new Error(String(message || 'network error'));
    p.done(err, { statusCode: statusCode | 0, headers: {} }, '', '');
  };

  /* ================= lx 对象 ================= */
  var EVENT_NAMES = { request: 'request', inited: 'inited', updateAlert: 'updateAlert', log: 'log' };

  globalThis.lx = {
    version: '2.1.0',
    env: 'mobile',
    EVENT_NAMES: EVENT_NAMES,
    currentScriptInfo: {
      name: '', description: '', version: '1.0.0', author: '',
      homepage: '', rawScript: '',
    },
    on: function (name, fn) { handlers[name] = fn; },
    send: send,
    request: request,
    utils: {
      crypto: {
        md5: function (s) { return hexFromBytes(md5(typeof s === 'string' ? s : toBytes(s))); },
        randomBytes: function (n) {
          var len = n || 8;
          var b = new Uint8Array(len);
          if (typeof crypto !== 'undefined' && crypto.getRandomValues) crypto.getRandomValues(b);
          else for (var i = 0; i < len; i++) b[i] = Math.floor(Math.random() * 256);
          return hexFromBytes(b);
        },
        aesEncrypt: aesEncrypt,
        rsaEncrypt: rsaEncrypt,
      },
      buffer: {
        from: function () { return bufferFrom.apply(null, arguments); },
        bufToString: function (buf, format) {
          var b = bufferFrom(buf);
          return b.toString(format || 'utf8');
        },
        concat: function (list) { return Buffer.concat(list || []); },
        getBytes: function (buf) {
          if (!buf) return new ArrayBuffer(0);
          var b = toBytes(buf);
          return b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength);
        },
        getUint8Array: function (buf) { return toBytes(buf); },
      },
    },
  };

  globalThis.Buffer = Buffer;
  globalThis.btoa = globalThis.btoa || function (s) { return b64FromBytes(bytesFromUtf8(String(s))); };
  globalThis.atob = globalThis.atob || function (s) { return utf8FromBytes(bytesFromB64(String(s))); };
  if (!globalThis.localStorage) {
    var mem = {};
    globalThis.localStorage = {
      getItem: function (k) { return Object.prototype.hasOwnProperty.call(mem, k) ? mem[k] : null; },
      setItem: function (k, v) { mem[k] = String(v); },
      removeItem: function (k) { delete mem[k]; },
      clear: function () { mem = {}; },
    };
  }
  if (typeof globalThis.require === 'undefined') {
    // 少量脚本会用 require('crypto') 之类，这里给个明确失败的替身，避免整段脚本崩溃
    globalThis.require = function (name) {
      throw new Error('require(' + name + ') is not available in this runtime');
    };
  }

  /* ================= 宿主控制 ================= */
  var loaded = false;

  window.__lxLoadScript = function (fileName, source) {
    handlers = {};
    sent = [];
    loaded = false;
    globalThis.lx.currentScriptInfo = {
      name: fileName || 'source.js',
      description: '',
      version: '1.0.0',
      author: '',
      homepage: '',
      rawScript: source || '',
    };
    try {
      // 用 Function 包一层：脚本顶层的 const/let 不会污染全局，也方便重复加载
      var fn = new Function(source);
      fn.call(globalThis);
      loaded = true;
      var req = typeof handlers[EVENT_NAMES.request] === 'function';
      return JSON.stringify({ ok: true, hasRequest: req, handlers: Object.keys(handlers) });
    } catch (e) {
      return JSON.stringify({ ok: false, error: String((e && e.message) || e) });
    }
  };

  window.__lxReady = function () { return loaded; };

  window.__lxCall = function (callId, source, action, infoJson) {
    var fn = handlers[EVENT_NAMES.request];
    if (typeof fn !== 'function') {
      window.__lxCallResult(callId, JSON.stringify({ ok: false, error: '该音源没有注册 request 事件' }));
      return;
    }
    var info;
    try { info = JSON.parse(infoJson || '{}'); } catch (e) { info = {}; }
    var settled = false;
    var finish = function (payload) {
      if (settled) return;
      settled = true;
      window.__lxCallResult(callId, JSON.stringify(payload));
    };
    try {
      var ret = fn({ source: source, action: action, info: info });
      if (ret && typeof ret.then === 'function') {
        ret.then(function (v) { finish({ ok: true, value: v }); })
          .catch(function (e) { finish({ ok: false, error: String((e && e.message) || e) }); });
      } else {
        finish({ ok: true, value: ret });
      }
    } catch (e) {
      finish({ ok: false, error: String((e && e.message) || e) });
    }
  };

  window.__lxCallResult = function (callId, payload) {
    try {
      if (window.YueYinCall && window.YueYinCall.onResult) window.YueYinCall.onResult(callId, payload);
    } catch (e) { log('callResult error: ' + e.message); }
  };

  window.__lxDeclared = function () {
    var out = {};
    for (var i = 0; i < sent.length; i++) {
      if (sent[i].name === EVENT_NAMES.inited && sent[i].data) out = sent[i].data;
    }
    return JSON.stringify(out || {});
  };

  window.__lxLogs = function () { return JSON.stringify(logBuf); };

  // 通知原生：运行时已就绪（脚本由原生随后注入）
  try { if (bridge) bridge.nativeReady(); } catch (e) { /* ignore */ }
})();
