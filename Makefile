COMPOSE_FILE ?= ops/docker/compose.yml
COMPOSE ?= docker compose

.PHONY: up down logs ps

up:
	$(COMPOSE) -f $(COMPOSE_FILE) up --build

down:
	$(COMPOSE) -f $(COMPOSE_FILE) down

logs:
	$(COMPOSE) -f $(COMPOSE_FILE) logs -f

ps:
	$(COMPOSE) -f $(COMPOSE_FILE) ps
