package com.mertbek.sharescreen.rtc

import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.Promise

@JsFun(
    "(iceServersJson) => new RTCPeerConnection({ iceServers: JSON.parse(iceServersJson), bundlePolicy: 'max-bundle' })"
)
internal external fun newPeerConnection(iceServersJson: String): JsAny

@JsFun("(pc) => pc.createOffer()")
internal external fun pcCreateOffer(pc: JsAny): Promise<JsAny?>

@JsFun("(pc) => pc.createAnswer()")
internal external fun pcCreateAnswer(pc: JsAny): Promise<JsAny?>

@JsFun("(init) => init.sdp")
internal external fun descriptionSdp(init: JsAny): String

@JsFun("(pc, type, sdp) => pc.setLocalDescription({ type: type, sdp: sdp })")
internal external fun pcSetLocalDescription(pc: JsAny, type: String, sdp: String): Promise<JsAny?>

@JsFun("(pc, type, sdp) => pc.setRemoteDescription({ type: type, sdp: sdp })")
internal external fun pcSetRemoteDescription(pc: JsAny, type: String, sdp: String): Promise<JsAny?>

@JsFun("(pc) => pc.remoteDescription != null")
internal external fun pcHasRemoteDescription(pc: JsAny): Boolean

@JsFun("(pc) => pc.signalingState")
internal external fun pcSignalingState(pc: JsAny): String

@JsFun("(pc) => pc.connectionState")
internal external fun pcConnectionState(pc: JsAny): String

@JsFun(
    "(pc, mid, index, candidate) => { pc.addIceCandidate({ sdpMid: mid === '' ? null : mid, sdpMLineIndex: index, candidate: candidate }).catch(() => {}); }"
)
internal external fun pcAddIceCandidate(pc: JsAny, mid: String, index: Int, candidate: String)

@JsFun("(pc, label) => pc.createDataChannel(label)")
internal external fun pcCreateDataChannel(pc: JsAny, label: String): JsAny

@JsFun("(pc) => { pc.restartIce(); }")
internal external fun pcRestartIce(pc: JsAny)

@JsFun("(pc) => { pc.onicecandidate = null; pc.onconnectionstatechange = null; pc.ontrack = null; pc.ondatachannel = null; pc.close(); }")
internal external fun pcClose(pc: JsAny)

@JsFun(
    "(pc, cb) => { pc.onicecandidate = (e) => { if (e.candidate) cb(e.candidate.sdpMid || '', e.candidate.sdpMLineIndex || 0, e.candidate.candidate); }; }"
)
internal external fun pcOnIceCandidate(pc: JsAny, callback: (String, Int, String) -> Unit)

@JsFun("(pc, cb) => { pc.onconnectionstatechange = () => cb(pc.connectionState); }")
internal external fun pcOnConnectionState(pc: JsAny, callback: (String) -> Unit)

@JsFun("(pc, cb) => { pc.ontrack = (e) => cb(e.track.kind, e.track, new MediaStream([e.track])); }")
internal external fun pcOnTrack(pc: JsAny, callback: (String, JsAny, JsAny) -> Unit)

@JsFun("(pc, cb) => { pc.ondatachannel = (e) => cb(e.channel); }")
internal external fun pcOnDataChannel(pc: JsAny, callback: (JsAny) -> Unit)

@JsFun(
    """(pc, stream, maxBitrate) => {
        for (const track of stream.getTracks()) {
            const init = { direction: 'sendonly', streams: [stream] };
            if (track.kind === 'video') {
                track.contentHint = 'detail';
                init.sendEncodings = [{ maxBitrate: maxBitrate, maxFramerate: 30 }];
            }
            const sender = pc.addTransceiver(track, init).sender;
            if (track.kind === 'video') {
                try {
                    const parameters = sender.getParameters();
                    parameters.degradationPreference = 'maintain-resolution';
                    sender.setParameters(parameters).catch(() => {});
                } catch (e) {}
            }
        }
    }"""
)
internal external fun pcAddSendingStream(pc: JsAny, stream: JsAny, maxBitrate: Int)

@JsFun(
    """(pc) => pc.getStats().then((report) => {
        let width = 0, height = 0, fps = -1, rtt = -1;
        report.forEach((s) => {
            if (s.type === 'inbound-rtp' && s.kind === 'video') {
                width = s.frameWidth || 0;
                height = s.frameHeight || 0;
                if (s.framesPerSecond !== undefined) fps = Math.round(s.framesPerSecond);
            }
            if (s.type === 'candidate-pair' && s.nominated && s.currentRoundTripTime !== undefined) {
                rtt = Math.round(s.currentRoundTripTime * 1000);
            }
        });
        return width + '|' + height + '|' + fps + '|' + rtt;
    })"""
)
internal external fun pcStats(pc: JsAny): Promise<JsString>

@JsFun("(dc) => dc.readyState")
internal external fun dcState(dc: JsAny): String

@JsFun("(dc) => dc.label")
internal external fun dcLabel(dc: JsAny): String

@JsFun("(dc, text) => { try { dc.send(text); return true; } catch (e) { return false; } }")
internal external fun dcSend(dc: JsAny, text: String): Boolean

@JsFun("(dc) => { dc.onopen = null; dc.onclose = null; dc.onmessage = null; dc.onerror = null; dc.close(); }")
internal external fun dcClose(dc: JsAny)

@JsFun("(dc, cb) => { dc.onopen = () => cb(); dc.onclose = () => cb(); dc.onerror = () => cb(); dc.onclosing = () => cb(); }")
internal external fun dcOnState(dc: JsAny, callback: () -> Unit)

@JsFun("(dc, cb) => { dc.onmessage = (e) => { if (typeof e.data === 'string') cb(e.data); }; }")
internal external fun dcOnMessage(dc: JsAny, callback: (String) -> Unit)

@JsFun(
    "(width, height, audio) => navigator.mediaDevices.getDisplayMedia({ video: { frameRate: 30, width: { max: width }, height: { max: height } }, audio: audio })"
)
internal external fun getDisplayMedia(width: Int, height: Int, audio: Boolean): Promise<JsAny?>

@JsFun("(stream) => { const s = stream.getVideoTracks()[0].getSettings(); return (s.width || 0) + '|' + (s.height || 0); }")
internal external fun streamVideoSize(stream: JsAny): String

@JsFun("(stream) => stream.getAudioTracks().length > 0")
internal external fun streamHasAudio(stream: JsAny): Boolean

@JsFun("(stream) => new MediaStream(stream.getVideoTracks())")
internal external fun streamVideoOnly(stream: JsAny): JsAny

@JsFun("(stream, cb) => { const track = stream.getVideoTracks()[0]; if (track) track.onended = () => cb(); }")
internal external fun streamOnEnded(stream: JsAny, callback: () -> Unit)

@JsFun("(stream) => { for (const track of stream.getTracks()) { track.onended = null; track.stop(); } }")
internal external fun streamStop(stream: JsAny)

@JsFun(
    """(stream) => {
        const wrapper = document.createElement('div');
        wrapper.style.overflow = 'hidden';
        wrapper.style.background = '#000';
        wrapper.style.pointerEvents = 'none';
        const video = document.createElement('video');
        video.autoplay = true;
        video.muted = true;
        video.playsInline = true;
        video.style.width = '100%';
        video.style.height = '100%';
        video.style.objectFit = 'fill';
        video.style.transformOrigin = '0 0';
        video.style.display = 'block';
        video.srcObject = stream;
        wrapper.appendChild(video);
        video.play().catch(() => {});
        return wrapper;
    }"""
)
internal external fun createVideoElement(stream: JsAny): JsAny

@JsFun("(wrapper, scale, x, y) => { wrapper.firstChild.style.transform = 'translate(' + x + 'px,' + y + 'px) scale(' + scale + ')'; }")
internal external fun videoSetTransform(wrapper: JsAny, scale: Float, x: Float, y: Float)

@JsFun(
    """(wrapper, cb) => {
        const video = wrapper.firstChild;
        const report = () => { if (video.videoWidth > 0 && video.videoHeight > 0) cb(video.videoWidth, video.videoHeight); };
        video.onloadedmetadata = report;
        video.onresize = report;
        report();
    }"""
)
internal external fun videoOnSize(wrapper: JsAny, callback: (Int, Int) -> Unit)

@JsFun("(wrapper) => { const video = wrapper.firstChild; video.onloadedmetadata = null; video.onresize = null; video.srcObject = null; }")
internal external fun videoRelease(wrapper: JsAny)

@JsFun("(track) => { const audio = new Audio(); audio.srcObject = new MediaStream([track]); audio.play().catch(() => {}); return audio; }")
internal external fun audioAttach(track: JsAny): JsAny

@JsFun("(audio, enabled) => { audio.muted = !enabled; }")
internal external fun audioSetEnabled(audio: JsAny, enabled: Boolean)

@JsFun("(audio) => { audio.srcObject = null; }")
internal external fun audioRelease(audio: JsAny)
