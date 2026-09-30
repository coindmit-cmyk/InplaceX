#!/usr/bin/env python3
"""Local LLM adapter for Agent Control Service.

Small standard-library bridge for routine Phase 2/3 work. It targets
OpenAI-compatible local endpoints first, so it can sit behind Ollama,
llama.cpp server, LM Studio, vLLM-compatible gateways, or another local
service without coupling the agent repo to one runtime.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import sys
from typing import Any
import urllib.error
import urllib.request


DEFAULT_SYSTEM_PROMPT = (
    "You are a local routine worker for ai-project-agent. "
    "Use only the provided context. Prefer concise, evidence-based output. "
    "Do not invent repository state."
)


def read_text(path: str | None) -> str:
    if not path:
        return sys.stdin.read()
    return Path(path).read_text(encoding="utf-8")


def load_config(path: str | None) -> dict[str, Any]:
    if not path:
        return {}
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    service = data.get("service", {}) if isinstance(data, dict) else {}
    local_llm = service.get("local_llm", {}) if isinstance(service, dict) else {}
    return local_llm if isinstance(local_llm, dict) else {}


def openai_compatible_chat(
    base_url: str,
    api_key: str,
    model: str,
    prompt: str,
    system_prompt: str,
    timeout_sec: int,
    response_format_json: bool = False,
) -> str:
    url = f"{base_url.rstrip('/')}/chat/completions"
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": prompt},
        ],
        "temperature": 0.2,
    }
    if response_format_json:
        payload["response_format"] = {"type": "json_object"}
    body = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(url, data=body, method="POST")
    request.add_header("Content-Type", "application/json")
    if api_key:
        request.add_header("Authorization", f"Bearer {api_key}")

    with urllib.request.urlopen(request, timeout=timeout_sec) as response:
        data = json.loads(response.read().decode("utf-8"))

    choices = data.get("choices") if isinstance(data, dict) else None
    if not choices:
        raise RuntimeError("Local LLM response has no choices.")
    first = choices[0]
    message = first.get("message") if isinstance(first, dict) else None
    content = message.get("content") if isinstance(message, dict) else None
    if not isinstance(content, str):
        raise RuntimeError("Local LLM response has no message content.")
    return content


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Call a local OpenAI-compatible LLM endpoint.")
    parser.add_argument("--config", help="Project registry JSON with service.local_llm settings")
    parser.add_argument("--prompt-file", help="Prompt/context file. Reads stdin when omitted.")
    parser.add_argument("--base-url", help="Override local LLM base URL")
    parser.add_argument("--model", help="Override local model name")
    parser.add_argument("--api-key-env", help="Environment variable holding local API key")
    parser.add_argument("--timeout", type=int, help="Request timeout seconds")
    parser.add_argument("--system-prompt", default=DEFAULT_SYSTEM_PROMPT)
    parser.add_argument("--response-format-json", action="store_true", help="Request JSON object mode when supported by the local endpoint.")
    parser.add_argument("--json", action="store_true", help="Emit JSON wrapper instead of plain text")
    args = parser.parse_args(argv)

    config = load_config(args.config)
    base_url = args.base_url or os.environ.get("LOCAL_LLM_BASE_URL") or str(config.get("base_url", "http://127.0.0.1:11434/v1"))
    model = args.model or os.environ.get("LOCAL_LLM_MODEL") or str(config.get("model", "qwen2.5-coder:14b"))
    api_key_env = args.api_key_env or str(config.get("api_key_env", "LOCAL_LLM_API_KEY"))
    api_key = os.environ.get(api_key_env, "")
    timeout_sec = args.timeout or int(os.environ.get("LOCAL_LLM_TIMEOUT_SEC") or config.get("timeout_sec", 120))
    prompt = read_text(args.prompt_file)

    try:
        output = openai_compatible_chat(base_url, api_key, model, prompt, args.system_prompt, timeout_sec, args.response_format_json)
    except (OSError, TimeoutError, urllib.error.URLError, json.JSONDecodeError, RuntimeError) as exc:
        error = {"ok": False, "backend": "openai_compatible", "base_url": base_url, "model": model, "error": str(exc)}
        print(json.dumps(error, ensure_ascii=False, indent=2), file=sys.stderr)
        return 2

    if args.json:
        print(json.dumps({"ok": True, "backend": "openai_compatible", "base_url": base_url, "model": model, "output": output}, ensure_ascii=False, indent=2))
    else:
        print(output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
