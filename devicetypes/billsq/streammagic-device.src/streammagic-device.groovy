metadata {
    definition (name: "StreamMagic Device", namespace: "billsq", author: "Qian Sheng") {
        capability "Actuator"
        capability "Switch"
        capability "Polling"
        capability "Refresh"
        capability "Sensor"
        capability "Music Player"
        capability "Switch Level"
    }

    simulator {}

    tiles(scale: 2) {
        multiAttributeTile(name: "mediaMulti", type:"mediaPlayer", width:6, height:4) {
            tileAttribute("device.status", key: "PRIMARY_CONTROL") {
                attributeState("paused", label:"Paused",)
                attributeState("playing", label:"Playing")
                attributeState("stopped", label:"Stopped")
            }

            tileAttribute("device.status", key: "MEDIA_STATUS") {
                attributeState("paused", label:"Paused", action:"music Player.play", nextState: "playing")
                attributeState("playing", label:"Playing", action:"music Player.pause", nextState: "paused")
                attributeState("stopped", label:"Stopped", action:"music Player.play", nextState: "playing")
            }

            tileAttribute("device.status", key: "PREVIOUS_TRACK") {
                attributeState("status", action:"music Player.previousTrack", defaultState: true)
            }

            tileAttribute("device.status", key: "NEXT_TRACK") {
                attributeState("status", action:"music Player.nextTrack", defaultState: true)
            }

            tileAttribute ("device.level", key: "SLIDER_CONTROL") {
                attributeState("level", action:"music Player.setLevel")
            }

            tileAttribute ("device.mute", key: "MEDIA_MUTED") {
                attributeState("unmuted", action:"music Player.mute", nextState: "muted")
                attributeState("muted", action:"music Player.unmute", nextState: "unmuted")
            }

            tileAttribute("device.trackDescription", key: "MARQUEE") {
                attributeState("trackDescription", label:"${currentValue}", defaultState: true)
            }
        }

        standardTile("switch", "device.switch", width: 2, height: 2, canChangeIcon: true) {
            state "on", label:'${name}', action:"switch.off", icon:"st.Electronics.electronics19", backgroundColor:"#00A0DC", nextState:"turningOff"
            state "off", label:'${name}', action:"switch.on", icon:"st.Electronics.electronics19", backgroundColor:"#ffffff", nextState:"turningOn"
            state "turningOn", label:'${name}', icon:"st.Electronics.electronics19", backgroundColor:"#00A0DC", nextState:"on"
            state "turningOff", label:'${name}', icon:"st.Electronics.electronics19", backgroundColor:"#ffffff", nextState:"off"
        }

        standardTile("refresh", "device.refresh", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
            state "default", label:"", action:"refresh.refresh", icon:"st.secondary.refresh"
        }

        main "switch"
        details(["mediaMulti", "switch", "refresh"])
    }
}

// parse events into attributes
def parse(String description) {
    log.debug "Parsing ${description}"

    def msg = parseLanMessage(description)
    def headerString = msg.header
    def events = []

    def bodyString = msg.body
    if (bodyString) {
        def body = new XmlSlurper().parseText(bodyString.replaceAll("[^\\x20-\\x7e]", ""))

         if (body?.Body?.GetPowerStateResponse?.RetPowerStateValue?.text()) {
            def powerState = body?.Body?.GetPowerStateResponse?.RetPowerStateValue?.text()

             log.trace "Got GetPowerStateResponse = ${powerState}"

            events << createEvent(name: "switch", value: (powerState == "ON") ? "on" : "off")
            if (powerState != "ON") {
                events << createEvent(name: "trackDescription", value: "")
            }
        } else if (body?.property?.PowerState?.text()) {
            def powerState = body?.property?.PowerState?.text()

             log.trace "Got new power state = ${powerState}"

            events << createEvent(name: "switch", value: (powerState == "ON") ? "on" : "off")
            if (powerState != "ON") {
                events << createEvent(name: "trackDescription", value: "")
            }
        } else if (body?.property?.LastChange?.text()) {
            def lastChange = body?.property?.LastChange?.text()
            def event = new XmlSlurper().parseText(lastChange)

            if (event?.InstanceID?.Volume?.size()) {
                // RenderingControl
                state.maxVolume = event?.InstanceID?.VolumeMax?.@val?.toInteger()
                def volume = event?.InstanceID?.Volume?.@val?.toInteger()
                def mute = event?.InstanceID?.Mute?.@val?.toBoolean() ? "muted" : "unmuted"
                def VolumeDB = event?.InstanceID?.VolumeDB?.@val?.toInteger()

                log.trace "Got volume=${volume} mute=${mute} maxVolume=${state.maxVolume} VolumeDB=${VolumeDB}"

                def level = (volume > 0) ? (VolumeDB + 24832) / 256 : 0

                events << createEvent(name: "level", value: level)
                events << createEvent(name: "mute", value: mute)
            } else {
                // AVTransport
                //log.debug "AVT lastChange ${lastChange}"

                def transportState = event?.InstanceID?.TransportState?.@val
                def status = "stopped"

                if (transportState == "PLAYING") {
                    status = "playing"
                } else if (transportState == "PAUSED_PLAYBACK") {
                    status = "paused"
                }

                log.trace "Got TransportState=${transportState} status=${status}"
                events << createEvent(name: "status", value: status)
            }
        } else if (body?.property?.PlaybackXML?.text()) {
            def playbackXML = body?.property?.PlaybackXML?.text()
            def playback = new XmlSlurper().parseText(playbackXML)

            def trackData = [:]
            def trackDesc = ""
            def trackText = ""

            if (device.currentValue("switch") == "off") {
                trackDesc = ""
            } else if (playback.'playback-details'?.'playlist-entry'?.size()) {
                trackData["name"] = playback?.'playback-details'?.'playlist-entry'?.title?.text()
                trackData["artist"] = playback?.'playback-details'?.'playlist-entry'?.artist?.text()
                trackData["album"] = playback?.'playback-details'?.'playlist-entry'?.album?.text()

                if (playback?.'playback-details'?.'playlist-entry'?.'spotify-source'?.size()) {
                    trackData["station"] = trackDesc = "Spotify: ${playback?.'playback-details'?.'playlist-entry'?.'spotify-source'?.text()}"
                    trackDesc += "\n"
                }

                trackDesc += "${trackData["name"]}\n"
                trackDesc += "${trackData["artist"]}\n"
                trackDesc += "${trackData["album"]}"
            }

            events << createEvent(name: "trackData", value: trackData)
            events << createEvent(name: "trackDescription", value: trackDesc)
        } else {
            log.debug "Unparsed body ${bodyString}"
        }
     }

     return events
}

private Integer convertHexToInt(hex) {
    Integer.parseInt(hex,16)
}

private String convertHexToIP(hex) {
    [convertHexToInt(hex[0..1]),convertHexToInt(hex[2..3]),convertHexToInt(hex[4..5]),convertHexToInt(hex[6..7])].join(".")
}

private String getHostAddress() {
    def ip = convertHexToIP(getDataValue("ip"))
    def port = convertHexToInt(getDataValue("port"))
    def host = "${ip}:${port}"

    log.debug "Using host: ${host} for device: ${device.label}"

    return host
}

private String getCallBackAddress() {
    def address = "${device.hub.getDataValue("localIP")}:${device.hub.getDataValue("localSrvPortTCP")}"

    log.debug "callbackAddress ${address}"

    return address
}

def installed() {
    log.debug "Executing installed() for ${device.label}"
    initialize()
}

def updated() {
    log.debug "Executing updated() for ${device.label}"
    initialize()
}

def initialize() {
    log.debug "Executing initialize() for ${device.label}"
    state.maxVolume = 30
    refresh()
}

def refresh() {
    log.debug "Executing refresh() for ${device.label}"

    unschedule(resubscribeRecivaRadio)
    unschedule(resubscribeRenderingControl)
    unschedule(resubscribeAVTransport)

    subscribe(["RecivaRadio", "RenderingControl", "AVTransport"])

    new physicalgraph.device.HubSoapAction(
        path:    getDataValue("RecivaRadioControlPath"),
        urn:     "urn:UuVol-com:service:UuVolControl:5",
        action:  "GetPowerState",
        headers: [Host: getHostAddress()]
    )
}

def poll() {
    log.debug "Executing poll() for ${device.label}"

    refresh()
}

def sync(ip, port) {
    def existingIp = getDataValue("ip")
    def existingPort = getDataValue("port")
    if (ip && ip != existingIp) {
        updateDataValue("ip", ip)
    }
    if (port && port != existingPort) {
        updateDataValue("port", port)
    }
}

def on() {
    log.debug "Executing on() for ${device.label}"

    new physicalgraph.device.HubSoapAction(
        path:    getDataValue("RecivaRadioControlPath"),
        urn:     "urn:UuVol-com:service:UuVolControl:5",
        action:  "SetPowerState",
        body:    [NewPowerStateValue: "ON"],
        headers: [Host: getHostAddress()]
    )
}

def off() {
    log.debug "Executing off() for ${device.label}"

    new physicalgraph.device.HubSoapAction(
        path:    getDataValue("RecivaRadioControlPath"),
        urn:     "urn:UuVol-com:service:UuVolControl:5",
        action:  "SetPowerState",
        body:    [NewPowerStateValue: "IDLE"],
        headers: [Host: getHostAddress()]
    )
}

def setLevel(level) {
    //def volume = Math.round(level * state.maxVolume / 100)
	def volume = ((level > 97) ? 97 : level) * 256 - 24832

    log.debug "Executing setLevel() for ${device.label} level=${level} volume=${volume}"

    new physicalgraph.device.HubSoapAction(
        path:    getDataValue("RenderingControlControlPath"),
        urn:     "urn:schemas-upnp-org:service:RenderingControl:1",
        action:  "SetVolumeDB",
        body:    [InstanceID: 0, Channel: "Master", DesiredVolume: volume],
        headers: [Host: getHostAddress()]
    )
}

def mute() {
    log.debug "Executing mute() for ${device.label}"

    new physicalgraph.device.HubSoapAction(
        path:    getDataValue("RenderingControlControlPath"),
        urn:     "urn:schemas-upnp-org:service:RenderingControl:1",
        action:  "SetMute",
        body:    [InstanceID: 0, Channel: "Master", DesiredMute: 1],
        headers: [Host: getHostAddress()]
    )
}

def unmute() {
    log.debug "Executing unmute() for ${device.label}"

    new physicalgraph.device.HubSoapAction(
        path:    getDataValue("RenderingControlControlPath"),
        urn:     "urn:schemas-upnp-org:service:RenderingControl:1",
        action:  "SetMute",
        body:    [InstanceID: 0, Channel: "Master", DesiredMute: 0],
        headers: [Host: getHostAddress()]
    )
}

def play() {
    log.debug "Executing play() for ${device.label}"

    new physicalgraph.device.HubSoapAction(
        path:    getDataValue("RecivaSimpleRemoteControlPath"),
        urn:     "urn:UuVol-com:service:UuVolSimpleRemote:1",
        action:  "KeyPressed",
        body:    [Key: "PLAY", Duration: "SHORT"],
        headers: [Host: getHostAddress()]
    )
}

def pause() {
    log.debug "Executing pause() for ${device.label}"

    new physicalgraph.device.HubSoapAction(
        path:    getDataValue("RecivaSimpleRemoteControlPath"),
        urn:     "urn:UuVol-com:service:UuVolSimpleRemote:1",
        action:  "KeyPressed",
        body:    [Key: "PAUSE", Duration: "SHORT"],
        headers: [Host: getHostAddress()]
    )
}

def stop() {
    log.debug "Executing stop() for ${device.label}"

    new physicalgraph.device.HubSoapAction(
        path:    getDataValue("RecivaSimpleRemoteControlPath"),
        urn:     "urn:UuVol-com:service:UuVolSimpleRemote:1",
        action:  "KeyPressed",
        body:    [Key: "STOP", Duration: "SHORT"],
        headers: [Host: getHostAddress()]
    )
}

def nextTrack() {
    log.debug "Executing nextTrack() for ${device.label}"

    new physicalgraph.device.HubSoapAction(
        path:    getDataValue("RecivaSimpleRemoteControlPath"),
        urn:     "urn:UuVol-com:service:UuVolSimpleRemote:1",
        action:  "KeyPressed",
        body:    [Key: "SKIP_NEXT", Duration: "SHORT"],
        headers: [Host: getHostAddress()]
    )
}

def previousTrack() {
    log.debug "Executing previousTrack() for ${device.label}"

    new physicalgraph.device.HubSoapAction(
        path:    getDataValue("RecivaSimpleRemoteControlPath"),
        urn:     "urn:UuVol-com:service:UuVolSimpleRemote:1",
        action:  "KeyPressed",
        body:    [Key: "SKIP_PREVIOUS", Duration: "SHORT"],
        headers: [Host: getHostAddress()]
    )
}

def playTrack(trackToPlay) {
    log.debug "Executing previousTrack() for ${device.label} track=${trackToPlay}"
}

def restoreTrack(trackToRestore) {
    log.debug "Executing restoreTrack() for ${device.label} track=${trackToRestore}"
}

def resumeTrack(trackToResume) {
    log.debug "Executing resumeTrack() for ${device.label} track=${trackToResume}"
}

def setTrack(trackToSet) {
    log.debug "Executing setTrack() for ${device.label} track=${trackToSet}"
}

// subscription
def subscribe(List events) {
    def address = getCallBackAddress()
    log.debug "Executing subscribe() on ${address} for events ${events}"

    events.each {
        sendHubCommand(new physicalgraph.device.HubAction([
                path: getDataValue("${it}EventPath"),
                method: "SUBSCRIBE",
                headers: [
                    "Host": "${getHostAddress()}",
                    "CALLBACK": "<http://${address}/>",
                    "NT": "upnp:event",
                    "TIMEOUT": "Second-1800"
                ]
            ],
            getDataValue("mac"),
            [callback: "subscribeHandler${it}"]
        ))
    }
}

def resubscribe(event, sid) {
    def address = getCallBackAddress()
    log.debug "Executing resubscribe() on ${address} for event ${event} sid ${sid}"

    sendHubCommand(new physicalgraph.device.HubAction([
            path: getDataValue("${event}EventPath"),
            method: "SUBSCRIBE",
            headers: [
                "Host": "${getHostAddress()}",
                "SID": "uuid:${sid}",
                "TIMEOUT": "Second-1800"
            ]
        ],
        getDataValue("mac"),
        [callback: "subscribeHandler${event}"]
    ))
}

def getSidAndTimeout(physicalgraph.device.HubResponse hubResponse) {
    def body = hubResponse.xml
    def headers = hubResponse.headers

    log.debug "Executing getSidAndTimeout() header=${headers} body=${body}"

    def sid = headers["SID"]
    sid -= "uuid:".trim()

    def timeoutString = headers["Timeout"]
    timeoutString -= "Second-".trim()
    def timeout = Integer.parseInt(timeoutString, 10)

    log.info "sid=${sid} timeout=${timeout}"

    return [sid: sid, timeout: timeout]
}

// RecivaRadio
def resubscribeRecivaRadio() {
    def sid = state.recivaRadioSid
    log.debug "Executing resubscribeRecivaRadio() sid=${sid}"

    subscribe(["RecivaRadio"])
}

void subscribeHandlerRecivaRadio(physicalgraph.device.HubResponse hubResponse) {
    def result = getSidAndTimeout(hubResponse)
    log.debug "Executing subscribeHandlerRecivaRadio() sid=${result.sid} timeout=${result.timeout}"

    state.recivaRadioSid = result.sid
    def timeout = result.timeout - 13 + (Math.random() * 6)

    log.info "Renew RecivaRadio subscription in ${timeout} seconds"

    unschedule(resubscribeRecivaRadio)
    runIn(timeout, resubscribeRecivaRadio)
}

// RenderingControl
def resubscribeRenderingControl() {
    def sid = state.renderingControlSid
    log.debug "Executing resubscribeRenderingControl() sid=${sid}"

    subscribe(["RenderingControl"])
}

void subscribeHandlerRenderingControl(physicalgraph.device.HubResponse hubResponse) {
    def result = getSidAndTimeout(hubResponse)
    log.debug "Executing subscribeHandlerRenderingControl() sid=${result.sid} timeout=${result.timeout}"

    state.renderingControlSid = result.sid
    def timeout = result.timeout - 13 + (Math.random() * 6)

    log.info "Renew RenderingControl subscription in ${timeout} seconds"

    unschedule(resubscribeRenderingControl)
    runIn(timeout, resubscribeRenderingControl)
}

// AVTransport
def resubscribeAVTransport() {
    def sid = state.avTransportSid
    log.debug "Executing resubscribeAVTransport() sid=${sid}"

    subscribe(["AVTransport"])
}

void subscribeHandlerAVTransport(physicalgraph.device.HubResponse hubResponse) {
    def result = getSidAndTimeout(hubResponse)
    log.debug "Executing subscribeHandlerAVTransport() sid=${result.sid} timeout=${result.timeout}"

    state.avTransportSid = result.sid
    def timeout = result.timeout - 13 + (Math.random() * 6)

    log.info "Renew AVTransport subscription in ${timeout} seconds"

    unschedule(resubscribeAVTransport)
    runIn(timeout, resubscribeAVTransport)
}
