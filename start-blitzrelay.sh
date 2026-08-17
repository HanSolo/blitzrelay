#!/bin/bash

docker run -d --memory="128m" --restart unless-stopped -v docker_logs:/docker_logs -e MQTT_HOST='host.docker.internal' -e MQTT_PORT='1883' -e MQTT_TOPIC='lightning/strikes' --expose=1883 --name blitzrelay hansolo/blitzrelay:latest