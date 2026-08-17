#!/bin/bash

docker run -d --memory="128m" --restart unless-stopped --add-host host.docker.internal:host-gateway -v docker_logs:/docker_logs -e MQTT_HOST='host.docker.internal' -e MQTT_PORT='1883' -e MQTT_TOPIC='lightning/strikes' --name blitzrelay hansolo/blitzrelay:latest