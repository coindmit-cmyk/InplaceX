#!/usr/bin/env python3
"""Small web UI and JSON API for the remote AiStudio chat bridge."""

from __future__ import annotations

import argparse
import html
import json
import os
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlparse

import remote_chat_bus


INDEX_HTML = """<!doctype html>
<html lang="ru">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>AiStudio Remote Chat</title>
  <style>
    :root {
      color-scheme: light dark;
      --line: #d8dee8;
      --panel: #f7f9fc;
      --text: #18202b;
      --muted: #657386;
      --accent: #1867c0;
    }
    @media (prefers-color-scheme: dark) {
      :root {
        --line: #303946;
        --panel: #151a21;
        --text: #e8edf4;
        --muted: #9aa6b5;
        --accent: #6aa9ff;
      }
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      font: 15px/1.45 system-ui, -apple-system, Segoe UI, sans-serif;
      color: var(--text);
      background: Canvas;
    }
    header {
      height: 52px;
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 0 16px;
      border-bottom: 1px solid var(--line);
      background: var(--panel);
      gap: 12px;
    }
    header strong { white-space: nowrap; }
    .header-title { display: flex; align-items: center; gap: 12px; min-width: 0; }
    .dashboard-link { min-height: 40px; display: inline-flex; align-items: center; color: var(--accent); font-size: 12px; font-weight: 700; text-decoration: none; white-space: nowrap; }
    #status {
      color: var(--muted);
      font-size: 13px;
      text-align: right;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    main { height: calc(100vh - 52px); display: grid; grid-template-columns: minmax(220px, 280px) 1fr; gap: 12px; padding: 12px; }
    aside, section.chat {
      width: 100%;
      min-width: 0;
      border: 1px solid var(--line);
      border-radius: 10px;
      background: var(--panel);
      padding: 12px;
      display: flex;
      flex-direction: column;
      min-height: 0;
    }
    .title-row { display: flex; gap: 8px; align-items: center; justify-content: space-between; margin-bottom: 8px; }
    .tabs { display: flex; flex-wrap: wrap; gap: 6px; }
    .tabs button { background: color-mix(in srgb, var(--panel) 65%, Canvas); border-color: var(--line); color: var(--text); }
    .tabs button.active { background: var(--accent); color: white; border-color: color-mix(in srgb, var(--accent) 55%, var(--line)); }
    .session-list { display: grid; gap: 6px; overflow: auto; margin-top: 10px; }
    .session {
      border: 1px solid var(--line);
      background: color-mix(in srgb, var(--panel) 65%, Canvas);
      border-radius: 8px;
      padding: 8px;
      cursor: pointer;
    }
    .session.active { border-color: var(--accent); box-shadow: 0 0 0 2px color-mix(in srgb, var(--accent) 30%, transparent) inset; }
    .session .name { font-weight: 600; }
    .session .meta { color: var(--muted); font-size: 12px; margin-top: 4px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .chat-header { display: grid; gap: 6px; margin-bottom: 8px; }
    #sessionTitle { margin: 0; }
    #sessionState { font-size: 12px; color: var(--muted); }
    #progressLine { font-size: 13px; color: var(--text); border: 1px dashed var(--line); border-radius: 8px; padding: 8px; background: Canvas; }
    #messageStatus { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; min-height: 30px; }
    .mode-controls { display: grid; grid-template-columns: 150px minmax(140px, 1fr) auto; gap: 8px; align-items: end; }
    .badge { display: inline-flex; align-items: center; border: 1px solid var(--line); border-radius: 999px; padding: 3px 8px; font-size: 12px; color: var(--text); background: Canvas; white-space: nowrap; }
    .badge.queued { border-color: #9db8df; background: color-mix(in srgb, #9db8df 14%, Canvas); }
    .badge.leased, .badge.running { border-color: #d19b36; background: color-mix(in srgb, #d19b36 16%, Canvas); }
    .badge.answered { border-color: #65a36e; background: color-mix(in srgb, #65a36e 14%, Canvas); }
    .badge.failed { border-color: #c85c5c; background: color-mix(in srgb, #c85c5c 14%, Canvas); }
    #messages {
      flex: 1;
      overflow: auto;
      border: 1px solid var(--line);
      border-radius: 8px;
      padding: 14px;
      background: color-mix(in srgb, var(--panel) 55%, Canvas);
      min-height: 180px;
    }
    .msg {
      max-width: 78%;
      padding: 10px 12px;
      margin: 8px 0;
      border: 1px solid var(--line);
      border-radius: 8px;
      white-space: pre-wrap;
      overflow-wrap: anywhere;
    }
    .user { margin-left: auto; border-color: color-mix(in srgb, var(--accent) 45%, var(--line)); }
    .assistant { margin-right: auto; background: Canvas; }
    .system { margin: 6px auto; border-color: color-mix(in srgb, #f7c949 40%, var(--line)); background: color-mix(in srgb, #f7c949 12%, Canvas); }
    .meta {
      font-size: 12px;
      color: var(--muted);
      margin-bottom: 4px;
    }
    .task-head {
      margin-top: 8px;
      display: flex;
      gap: 8px;
      align-items: center;
      justify-content: space-between;
    }
    .task-head h3 { margin: 0; }
    #taskList {
      border: 1px solid var(--line);
      border-radius: 8px;
      padding: 8px;
      background: Canvas;
      min-height: 80px;
      max-height: 210px;
      overflow: auto;
      display: grid;
      gap: 8px;
      margin-top: 8px;
    }
    .task {
      border: 1px solid var(--line);
      border-radius: 8px;
      padding: 8px;
      background: color-mix(in srgb, var(--panel) 45%, Canvas);
      display: grid;
      gap: 6px;
    }
    .task .row {
      display: grid;
      grid-template-columns: minmax(120px, 1fr) 88px 88px 92px 42px 42px 58px;
      gap: 6px;
      align-items: center;
    }
    .task .row select,
    .task .row input {
      min-height: 32px;
      max-height: 34px;
      padding: 0 8px;
    }
    .task .title { font-size: 13px; }
    .task .meta small { color: var(--muted); }
    .row-small { display: flex; gap: 8px; align-items: center; }
    label { font-size: 12px; color: var(--muted); display: block; margin-bottom: 4px; }
    form {
      margin-top: 8px;
      display: grid;
      grid-template-columns: 1fr auto;
      gap: 10px;
      align-items: end;
    }
    textarea, input, select {
      min-width: 0;
      min-height: 56px;
      max-height: 180px;
      resize: vertical;
      padding: 10px;
      border: 1px solid var(--line);
      border-radius: 8px;
      font: inherit;
      color: inherit;
      background: Canvas;
    }
    input, select { min-height: 34px; max-height: 40px; }
    .toolbar {
      display: grid;
      gap: 8px;
      margin-top: 8px;
      grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
    }
    .toolbar select, .toolbar input, .toolbar .button {
      min-height: 34px;
      border: 1px solid color-mix(in srgb, var(--accent) 65%, var(--line));
      border-radius: 8px;
      padding: 0 10px;
      background: Canvas;
      color: inherit;
      text-align: left;
      font: inherit;
    }
    button {
      border: 1px solid color-mix(in srgb, var(--accent) 65%, var(--line));
      border-radius: 8px;
      background: var(--accent);
      color: white;
      font: inherit;
      padding: 0 16px;
      min-height: 40px;
      cursor: pointer;
    }
    button.secondary {
      background: transparent;
      color: var(--text);
    }
    button:disabled { opacity: .55; cursor: wait; }
    .project-picker { display: grid; grid-template-columns: 1fr; gap: 6px; margin: 8px 0 10px; }
    .task-context {
      padding: 10px 12px;
      border: 1px solid color-mix(in srgb, var(--accent) 32%, var(--line));
      border-radius: 9px;
      color: var(--text);
      background: color-mix(in srgb, var(--accent) 8%, Canvas);
      font-size: 12px;
      overflow-wrap: anywhere;
    }
    .task-context strong { display: block; margin-bottom: 3px; color: var(--accent); }
    .empty {
      color: var(--muted);
      font-size: 13px;
      padding: 8px;
    }
    @media (max-width: 820px) {
      header { height: auto; min-height: 48px; padding: 8px 10px; align-items: flex-start; }
      header strong { font-size: 14px; }
      #status { white-space: normal; text-align: right; max-width: 48vw; }
      main { width: 100%; min-width: 0; height: auto; min-height: calc(100vh - 52px); grid-template-columns: minmax(0, 1fr); gap: 8px; padding: 8px; }
      aside, section.chat { border-radius: 8px; padding: 10px; }
      aside { max-height: 42vh; }
      .title-row { align-items: stretch; display: grid; grid-template-columns: 1fr; }
      .tabs { flex-wrap: nowrap; overflow-x: auto; padding-bottom: 2px; }
      .tabs button { min-width: max-content; }
      .session-list { max-height: 150px; }
      .chat-header { gap: 8px; }
      .mode-controls { grid-template-columns: 1fr; }
      #sessionTitle { font-size: 16px; line-height: 1.25; overflow-wrap: anywhere; }
      #messages { min-height: 44vh; padding: 10px; }
      .msg { max-width: 100%; }
      form, #taskForm { grid-template-columns: 1fr !important; }
      textarea { min-height: 76px; }
      .task-head, .row-small { align-items: stretch; flex-direction: column; }
      .task .row { grid-template-columns: 1fr 1fr; }
      .task .row button { width: 100%; }
      button { min-height: 40px; }
    }
  </style>
</head>
<body>
  <header>
    <div class="header-title">
      <a class="dashboard-link" href="/">← Dashboard</a>
      <strong>AiStudio Remote Chat</strong>
    </div>
    <span id="status" class="status">connecting</span>
  </header>
  <main>
    <aside>
      <div class="title-row">
        <strong>Проекты</strong>
        <button id="newProjectSession" type="button" class="secondary">Новый чат</button>
        <button id="recomputeProjectEta" type="button" class="secondary">Пересчитать ETA проекта</button>
      </div>
      <div class="project-picker">
        <label for="projectSelect">Проект для нового чата</label>
        <select id="projectSelect"></select>
        <input id="customProject" placeholder="Или project_id вручную" />
      </div>
      <div id="projectTabs" class="tabs"></div>
      <h3 style="margin: 14px 0 8px;">Чаты проекта</h3>
      <div id="sessions" class="session-list"></div>
    </aside>
    <section class="chat">
      <div class="chat-header">
        <h2 id="sessionTitle" style="margin:0;">Нет активного чата</h2>
        <div id="sessionState">Выберите чат или создайте новый</div>
        <div id="taskContext" class="task-context" hidden></div>
        <div id="messageStatus"></div>
        <div id="progressLine" class="status">Нет прогресса</div>
        <div class="mode-controls">
          <div>
            <label for="chatMode">Режим</label>
            <select id="chatMode">
              <option value="general">General</option>
              <option value="worker">Worker</option>
              <option value="architect">Architect</option>
              <option value="dispatcher">Dispatcher</option>
              <option value="integrator">Integrator</option>
              <option value="finalizer">Finalizer</option>
              <option value="automation_debug">Automation debug</option>
              <option value="decision_council">Decision Council</option>
              <option value="project_design">Project Design</option>
            </select>
          </div>
          <div>
            <label for="chatSkill">Skill</label>
            <input id="chatSkill" placeholder="например: openai-docs" />
          </div>
          <button id="saveChatMode" type="button" class="secondary">Сохранить режим</button>
        </div>
      </div>
      <div id="messages" aria-live="polite"></div>
      <div class="row-small">
        <button id="scrollDown" type="button" class="secondary">▼ Вниз</button>
      </div>

      <form id="form">
        <textarea id="text" placeholder="Написать сообщение..." autofocus></textarea>
        <button id="send" type="submit">Отправить</button>
      </form>
      <div class="row-small">
        <button id="clarify" type="button" class="secondary">Уточнить задачу</button>
      </div>

      <section class="task-head">
        <h3>Прогресс выполнения</h3>
        <div class="row-small">
          <label for="sessionStatus">Статус:</label>
          <select id="sessionStatus">
            <option value="planning">planning</option>
            <option value="running">running</option>
            <option value="paused">paused</option>
            <option value="blocked">blocked</option>
            <option value="done">done</option>
            <option value="cancelled">cancelled</option>
          </select>
          <label for="sessionEta">ETA мин:</label>
          <input id="sessionEta" type="number" min="0" step="1" placeholder="ETA" />
          <button id="saveSessionMeta" type="button" class="secondary">Сохранить статус</button>
        </div>
      </section>
      <div id="taskList"></div>

      <form id="taskForm" style="grid-template-columns: 1fr 140px auto;">
        <textarea id="taskText" placeholder="Новый шаг в плане..." style="min-height: 40px; max-height: 90px;"></textarea>
        <input id="taskEta" type="number" min="0" step="1" placeholder="ETA мин"/>
        <button type="submit">Добавить шаг</button>
      </form>
    </section>
  </main>
  <script>
    const API_PREFIX = window.location.pathname.endsWith('/') ? '..' : '.';
    const URL_PARAMS = new URLSearchParams(window.location.search);
    const REQUESTED_PROJECT = String(URL_PARAMS.get('project_id') || '').trim();
    const REQUESTED_TASK = String(URL_PARAMS.get('task_id') || '').trim();
    const statusEl = document.getElementById('status');
    const messagesEl = document.getElementById('messages');
    let followTail = false;
    const projectTabsEl = document.getElementById('projectTabs');
    const projectSelectEl = document.getElementById('projectSelect');
    const customProjectEl = document.getElementById('customProject');
    const sessionsEl = document.getElementById('sessions');
    const sessionTitleEl = document.getElementById('sessionTitle');
    const sessionStateEl = document.getElementById('sessionState');
    const taskContextEl = document.getElementById('taskContext');
    const messageStatusEl = document.getElementById('messageStatus');
    const progressLineEl = document.getElementById('progressLine');
    const text = document.getElementById('text');
    const send = document.getElementById('send');
    const form = document.getElementById('form');
    const clarify = document.getElementById('clarify');
    const sessionStatusEl = document.getElementById('sessionStatus');
    const sessionEtaEl = document.getElementById('sessionEta');
    const saveSessionMeta = document.getElementById('saveSessionMeta');
    const chatModeEl = document.getElementById('chatMode');
    const chatSkillEl = document.getElementById('chatSkill');
    const saveChatMode = document.getElementById('saveChatMode');
    const taskForm = document.getElementById('taskForm');
    const taskText = document.getElementById('taskText');
    const taskEta = document.getElementById('taskEta');
    const taskListEl = document.getElementById('taskList');
    const newProjectSession = document.getElementById('newProjectSession');
    const recomputeProjectEta = document.getElementById('recomputeProjectEta');
    const scrollDownBtn = document.getElementById('scrollDown');

    const STORAGE_SESSION_KEY = 'aistudio.remoteChat.sessionId';
    const STORAGE_PROJECT_KEY = 'aistudio.remoteChat.projectId';

    let state = {sessions: [], messages: [], tasks: []};
    let projectOptions = ['all'];
    let selectedProject = REQUESTED_PROJECT || localStorage.getItem(STORAGE_PROJECT_KEY) || 'all';
    let selectedSessionId = localStorage.getItem(STORAGE_SESSION_KEY) || '';
    let renderedSessionId = '';
    if (REQUESTED_PROJECT) {
      localStorage.setItem(STORAGE_PROJECT_KEY, REQUESTED_PROJECT);
    }
    if (REQUESTED_TASK) {
      taskContextEl.hidden = false;
      taskContextEl.innerHTML = `<strong>Контекст из дашборда</strong>Задача <code>${esc(REQUESTED_TASK)}</code> · проект ${esc(selectedProject)}`;
      text.placeholder = `Обсудить задачу ${REQUESTED_TASK}...`;
    }

    function apiUrl(path) {
      const clean = String(path || '').replace(/^\\/+/, '');
      return `${API_PREFIX}/${clean}`;
    }

    function esc(value) {
      return String(value || '').replace(/[&<>"']/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'": '&#39;'}[ch]));
    }

    function labelForProject(value) {
      return value ? String(value) : 'Без проекта';
    }

    function normalizeProject(value) {
      const clean = String(value || '').trim();
      return clean || 'all';
    }

    function projectLabel(value) {
      if (value === '__system__') return 'Системные';
      return value === 'all' ? 'Все проекты' : labelForProject(value);
    }

    function isSystemSession(session) {
      const channel = String(session?.channel || '').toLowerCase();
      const mode = String(session?.chat_mode || '').toLowerCase();
      return channel === 'automation-debug' || channel === 'system' || channel === 'monitor' || mode === 'automation_debug';
    }

    function userSessions() {
      return (state.sessions || []).filter(item => !isSystemSession(item));
    }

    function systemSessions() {
      return (state.sessions || []).filter(isSystemSession);
    }

    function sortSessions(items) {
      return [...items].sort((a, b) => {
        const left = String(a.updated_at || '');
        const right = String(b.updated_at || '');
        if (left !== right) return left < right ? 1 : -1;
        return String(a.session_id || '').localeCompare(String(b.session_id || ''));
      });
    }

    function activeSession() {
      return (state.sessions || []).find(item => String(item.session_id || '') === String(selectedSessionId || ''));
    }

    function isNearBottom() {
      const threshold = 24;
      return (messagesEl.scrollTop + messagesEl.clientHeight) >= (messagesEl.scrollHeight - threshold);
    }

    async function api(path, options = {}) {
      const response = await fetch(apiUrl(path), {headers: {'Content-Type': 'application/json'}, ...options});
      const payload = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(payload.error || response.statusText);
      return payload;
    }

    async function loadState() {
      const query = selectedProject && selectedProject !== 'all' ? `?project_id=${encodeURIComponent(selectedProject)}` : '';
      const payload = await api(`/api/chat/state${query}`);
      state = payload || {};
      state.sessions = Array.isArray(payload.sessions) ? payload.sessions : [];
      state.messages = Array.isArray(payload.messages) ? payload.messages : [];
      state.tasks = Array.isArray(payload.tasks) ? payload.tasks : [];
    }

    async function loadProjectOptions() {
      try {
        const payload = await api('/api/chat/projects');
        const values = Array.isArray(payload.projects) ? payload.projects.map(normalizeProject) : [];
        projectOptions = Array.from(new Set(['all', ...values])).sort((a, b) => (a === 'all' ? -1 : b === 'all' ? 1 : String(a).localeCompare(String(b))));
      } catch {
        const fallback = new Set(['all']);
        for (const session of userSessions()) fallback.add(normalizeProject(session.project_id));
        projectOptions = Array.from(fallback);
      }
    }

    function renderProjectSelect() {
      const values = new Set(projectOptions.length ? projectOptions : ['all']);
      for (const session of userSessions()) values.add(normalizeProject(session.project_id));
      const sorted = Array.from(values).sort((a, b) => (a === 'all' ? -1 : b === 'all' ? 1 : String(a).localeCompare(String(b))));
      projectSelectEl.innerHTML = sorted.map(projectId => {
        const selected = projectId === selectedProject ? ' selected' : '';
        return `<option value="${esc(projectId)}"${selected}>${esc(projectLabel(projectId))}</option>`;
      }).join('');
    }

    function buildProjects() {
      const projects = new Set(projectOptions.length ? projectOptions : ['all']);
      for (const session of userSessions()) {
        projects.add(normalizeProject(session.project_id));
      }
      if (systemSessions().length) {
        projects.add('__system__');
      }
      const sorted = Array.from(projects).sort((a, b) => (a === 'all' ? -1 : b === 'all' ? 1 : String(a).localeCompare(String(b))));
      projectTabsEl.innerHTML = sorted.map(projectId => {
        const title = projectLabel(projectId);
        const active = projectId === selectedProject ? 'active' : '';
        return `<button type="button" class="${active}" data-project="${esc(projectId)}">${esc(title)}</button>`;
      }).join('');
      projectTabsEl.querySelectorAll('button').forEach(btn => {
        btn.addEventListener('click', () => {
          selectedProject = btn.getAttribute('data-project') || 'all';
          localStorage.setItem(STORAGE_PROJECT_KEY, selectedProject);
          selectedSessionId = '';
          localStorage.setItem(STORAGE_SESSION_KEY, '');
          refresh();
        });
      });
    }

    function filteredSessions() {
      if (selectedProject === '__system__') {
        return sortSessions(systemSessions());
      }
      const sessions = userSessions();
      if (selectedProject === 'all') {
        return sortSessions(sessions);
      }
      return sortSessions(sessions.filter(item => normalizeProject(item.project_id) === selectedProject));
    }

    function taskLine(task) {
      const etaText = task.eta_minutes != null ? `${task.eta_minutes} мин` : '—';
      return `${esc(task.status || 'pending')} • ETA ${etaText}`;
    }

    function taskStatusOptions(task) {
      const status = String(task.status || 'pending');
      return ['pending', 'in_progress', 'done', 'blocked', 'cancelled']
        .map(item => `<option value="${item}"${item === status ? ' selected' : ''}>${item}</option>`)
        .join('');
    }

    function renderSessionState(session) {
      if (!session) {
        sessionTitleEl.textContent = 'Нет активного чата';
        sessionStateEl.textContent = 'Выберите чат или создайте новый';
        messageStatusEl.innerHTML = '<span class="badge">Сообщений нет</span>';
        progressLineEl.textContent = 'Нет прогресса';
        sessionStatusEl.value = 'planning';
        sessionEtaEl.value = '';
        chatModeEl.value = 'general';
        chatSkillEl.value = '';
        taskListEl.innerHTML = '';
        return;
      }
      const title = session.title || session.session_id;
      const status = session.status || 'planning';
      const chatMode = session.chat_mode || 'general';
      const skill = session.skill || '';
      const eta = session.eta_minutes_estimate != null ? `${session.eta_minutes_estimate} мин` : '—';
      const activeStep = session.active_step || '';
      sessionTitleEl.textContent = `${title} (${session.session_id})`;
      sessionStateEl.textContent = `Проект: ${labelForProject(session.project_id || 'all')} • Режим: ${chatMode}${skill ? `/${skill}` : ''} • Статус: ${status} • ETA: ${eta}`;
      const progressLine = `${session.progress_line || '0/0 (0%)'} • Active: ${activeStep || '—'}`;
      progressLineEl.textContent = progressLine;
      sessionStatusEl.value = status;
      sessionEtaEl.value = session.eta_minutes_estimate == null ? '' : String(session.eta_minutes_estimate);
      chatModeEl.value = chatMode;
      chatSkillEl.value = skill;
      const tasks = state.tasks
        .filter(item => String(item.session_id || '') === String(session.session_id || ''))
        .sort((a, b) => {
          const l = Number(a.order_index || 0);
          const r = Number(b.order_index || 0);
          if (l !== r) return l - r;
          return String(a.created_at || '').localeCompare(String(b.created_at || ''));
        });
      if (!tasks.length) {
        taskListEl.innerHTML = '<div class="empty">План еще пуст.</div>';
        return;
      }
      taskListEl.innerHTML = tasks.map(task => {
        const line = taskLine(task);
        const textValue = esc(task.text || '').replace(/\\n/g, '<br/>');
        return `<article class="task" data-task="${esc(task.task_id)}">
          <div class="title">${textValue}</div>
          <div class="meta"><small>${line}</small></div>
          <div class="row">
            <select class="taskStatus" data-task="${esc(task.task_id)}">
              ${taskStatusOptions(task)}
            </select>
            <input class="taskOrder" type="number" min="1" step="1" value="${task.order_index == null ? '' : esc(task.order_index)}" data-task="${esc(task.task_id)}" />
            <input class="taskEta" type="number" min="0" step="1" value="${task.eta_minutes == null ? '' : esc(task.eta_minutes)}" data-task="${esc(task.task_id)}" />
            <button type="button" class="secondary updateTask" data-task="${esc(task.task_id)}">Обновить</button>
            <button type="button" class="secondary moveTask" data-task="${esc(task.task_id)}" data-direction="up" title="Поднять шаг">&#8593;</button>
            <button type="button" class="secondary moveTask" data-task="${esc(task.task_id)}" data-direction="down" title="Опустить шаг">&#8595;</button>
            <button type="button" class="secondary deleteTask" data-task="${esc(task.task_id)}">Удалить</button>
          </div>
        </article>`;
      }).join('');

      taskListEl.querySelectorAll('.updateTask').forEach(button => {
        button.addEventListener('click', async event => {
          const taskId = event.currentTarget.getAttribute('data-task') || '';
          const taskBlock = Array.from(taskListEl.querySelectorAll('.task')).find(item => String(item.getAttribute('data-task') || '') === taskId);
          if (!taskBlock) return;
          const status = taskBlock.querySelector('.taskStatus')?.value || '';
          const etaValue = taskBlock.querySelector('.taskEta')?.value || '';
          const orderValue = taskBlock.querySelector('.taskOrder')?.value || '';
          await api('/api/chat/tasks/update', {method: 'POST', body: JSON.stringify({
            task_id: taskId,
            status,
            eta_minutes: etaValue === '' ? null : Number(etaValue),
            order_index: orderValue === '' ? null : Number(orderValue),
          })});
          await refresh();
        });
      });

      taskListEl.querySelectorAll('.deleteTask').forEach(button => {
        button.addEventListener('click', async event => {
          const taskId = event.currentTarget.getAttribute('data-task') || '';
          if (!window.confirm(`Удалить шаг ${taskId}?`)) return;
          await api('/api/chat/tasks/delete', {method: 'POST', body: JSON.stringify({task_id: taskId})});
          await refresh();
        });
      });

      taskListEl.querySelectorAll('.moveTask').forEach(button => {
        button.addEventListener('click', async event => {
          const taskId = event.currentTarget.getAttribute('data-task') || '';
          const direction = event.currentTarget.getAttribute('data-direction') || '';
          const taskItems = state.tasks
            .filter(item => String(item.session_id || '') === String(session.session_id || ''))
            .sort((a, b) => {
              const l = Number(a.order_index || 0);
              const r = Number(b.order_index || 0);
              if (l !== r) return l - r;
              return String(a.created_at || '').localeCompare(String(b.created_at || ''));
            });
          const index = taskItems.findIndex(item => String(item.task_id || '') === taskId);
          if (index < 0) return;
          const target = direction === 'up' ? index - 1 : index + 1;
          if (target < 0 || target >= taskItems.length) return;
          const currentTask = taskItems[index];
          const targetTask = taskItems[target];
          const currentOrder = Number(currentTask.order_index || index + 1);
          const targetOrder = Number(targetTask.order_index || target + 1);
          await api('/api/chat/tasks/update', {method: 'POST', body: JSON.stringify({
            task_id: currentTask.task_id,
            order_index: targetOrder,
          })});
          await api('/api/chat/tasks/update', {method: 'POST', body: JSON.stringify({
            task_id: targetTask.task_id,
            order_index: currentOrder,
          })});
          await refresh();
        });
      });
    }

    function renderMessageStatus(session) {
      if (!session) {
        messageStatusEl.innerHTML = '<span class="badge">Сообщений нет</span>';
        return;
      }
      const messages = state.messages.filter(item => String(item.session_id || '') === String(session.session_id || ''));
      const counts = {queued: 0, leased: 0, running: 0, answered: 0, failed: 0};
      for (const item of messages) {
        const stateValue = String(item.state || '');
        if (Object.prototype.hasOwnProperty.call(counts, stateValue)) counts[stateValue] += 1;
      }
      const active = counts.queued + counts.leased + counts.running;
      const latestActive = [...messages].reverse().find(item => ['queued', 'leased', 'running'].includes(String(item.state || '')));
      const activeText = active ? `Идет работа: ${active} (${latestActive?.state || 'active'})` : 'Работы по сообщениям нет';
      messageStatusEl.innerHTML = [
        `<span class="badge ${active ? 'running' : 'answered'}">${esc(activeText)}</span>`,
        `<span class="badge queued">queued ${counts.queued}</span>`,
        `<span class="badge leased">claimed ${counts.leased}</span>`,
        `<span class="badge running">running ${counts.running}</span>`,
        `<span class="badge answered">answered ${counts.answered}</span>`,
        `<span class="badge failed">failed ${counts.failed}</span>`,
      ].join('');
    }

    function renderMessages(session) {
      if (!session) {
        messagesEl.innerHTML = '<div class="empty">Нет активных сообщений.</div>';
        renderedSessionId = '';
        return;
      }
      const previousSession = renderedSessionId;
      const preserveBottom = previousSession !== session.session_id || (followTail && isNearBottom());
      const previousScrollTop = messagesEl.scrollTop;
      const filtered = state.messages.filter(item => String(item.session_id || '') === String(session.session_id || ''));
      const items = filtered.slice(-120);
      if (!items.length) {
        messagesEl.innerHTML = '<div class="empty">История пуста.</div>';
        renderedSessionId = session.session_id;
        return;
      }
      messagesEl.innerHTML = items.map(item => {
        const stateValue = esc(item.state || 'unknown');
        return `<article class="msg ${esc(item.role)}">
          <div class="meta">${esc(item.role)} · <span class="badge ${stateValue}">${stateValue}</span> · ${esc(item.created_at || '')}</div>
          <div>${esc(item.text || '').replace(/\\n/g, '<br/>')}</div>
        </article>`;
      }).join('');
      if (preserveBottom) {
        messagesEl.scrollTop = messagesEl.scrollHeight;
      } else {
        const maxScrollTop = Math.max(0, messagesEl.scrollHeight - messagesEl.clientHeight);
        messagesEl.scrollTop = Math.min(previousScrollTop, maxScrollTop);
      }
      renderedSessionId = session.session_id;
    }

    function renderSessions() {
      const list = filteredSessions();
      sessionsEl.innerHTML = '';
      if (!list.length) {
        sessionsEl.innerHTML = selectedProject === '__system__'
          ? '<div class="empty">Системных чатов нет.</div>'
          : '<div class="empty">Нет чатов для данного проекта.</div>';
        return;
      }
      const sessionNode = list.map(item => {
        const isActive = String(item.session_id || '') === String(selectedSessionId || '');
        const mode = item.chat_mode || 'general';
        const skill = item.skill ? `/${item.skill}` : '';
        const line = `${item.progress_line || '0/0 (0%)'} • ${item.status || 'planning'} • ${mode}${skill}`;
        return `<div class="session${isActive ? ' active' : ''}" data-session="${esc(item.session_id)}" title="${esc(item.session_id)}">
          <div class="name">${esc(item.title || item.session_id)}</div>
          <div class="meta">${esc(labelForProject(item.project_id || 'all'))} • ${esc(line)}</div>
        </div>`;
      }).join('');
      sessionsEl.innerHTML = sessionNode;
      sessionsEl.querySelectorAll('.session').forEach(node => {
        node.addEventListener('click', () => {
          selectedSessionId = node.getAttribute('data-session') || '';
          localStorage.setItem(STORAGE_SESSION_KEY, selectedSessionId);
          followTail = true;
          const session = activeSession();
          renderSessionState(session);
          renderMessageStatus(session);
          renderMessages(session);
          statusEl.textContent = 'online';
        });
      });
    }

    async function ensureSession(projectId = selectedProject) {
      if (!selectedSessionId) {
        const payload = await api('/api/chat/sessions', {
          method: 'POST',
          body: JSON.stringify({channel: 'web', project_id: projectId === 'all' ? '' : projectId, chat_mode: chatModeEl.value || 'general', skill: chatSkillEl.value.trim()}),
        });
        selectedSessionId = payload.session.session_id || '';
        localStorage.setItem(STORAGE_SESSION_KEY, selectedSessionId);
        return;
      }
      try {
        const session = activeSession();
        if (session) return;
      } catch {
        selectedSessionId = '';
        localStorage.setItem(STORAGE_SESSION_KEY, '');
      }
    }

    async function postMessage(textValue, asClarify = false) {
      if (!textValue) return;
      await ensureSession();
      const endpoint = asClarify ? '/api/chat/clarify' : '/api/chat/messages';
      await api(endpoint, {
        method: 'POST',
        body: JSON.stringify({
          session_id: selectedSessionId,
          text: textValue,
        }),
      });
    }

    function selectedProjectForNewChat() {
      const custom = customProjectEl.value.trim();
      if (custom) return custom;
      return projectSelectEl.value || selectedProject || 'all';
    }

    async function refresh() {
      try {
        await loadState();
        await loadProjectOptions();
        renderProjectSelect();
        buildProjects();
        const sessions = filteredSessions();
        if (selectedSessionId && !sessions.find(item => String(item.session_id || '') === String(selectedSessionId || ''))) {
          selectedSessionId = '';
          localStorage.setItem(STORAGE_SESSION_KEY, '');
        }
        if (!selectedSessionId && sessions.length) {
          selectedSessionId = String(sessions[0].session_id || '');
          localStorage.setItem(STORAGE_SESSION_KEY, selectedSessionId);
        }
        renderSessions();
        const session = activeSession();
        renderSessionState(session);
        renderMessageStatus(session);
        renderMessages(session);
        statusEl.textContent = 'online';
      } catch (error) {
        statusEl.textContent = error.message || 'offline';
      }
    }

    form.addEventListener('submit', async event => {
      event.preventDefault();
      const value = text.value.trim();
      if (!value) return;
      send.disabled = true;
      try {
        followTail = true;
        await postMessage(value, false);
        text.value = '';
        await refresh();
      } catch (error) {
        statusEl.textContent = error.message || 'send failed';
      } finally {
        send.disabled = false;
      }
    });

    clarify.addEventListener('click', async () => {
      const value = text.value.trim();
      if (!value) return;
      try {
        followTail = true;
        await postMessage(value, true);
        text.value = '';
        await refresh();
      } catch (error) {
        statusEl.textContent = error.message || 'clarify failed';
      }
    });

    taskForm.addEventListener('submit', async event => {
      event.preventDefault();
      if (!selectedSessionId) {
        await ensureSession();
      }
      followTail = true;
      const value = taskText.value.trim();
      if (!value || !selectedSessionId) return;
      await api('/api/chat/tasks', {method: 'POST', body: JSON.stringify({
        session_id: selectedSessionId,
        text: value,
        eta_minutes: taskEta.value === '' ? null : Number(taskEta.value),
      })});
      taskText.value = '';
      taskEta.value = '';
      await refresh();
    });

    saveSessionMeta.addEventListener('click', async () => {
      if (!selectedSessionId) return;
      const status = sessionStatusEl.value;
      const etaValue = sessionEtaEl.value === '' ? null : Number(sessionEtaEl.value);
      await api('/api/chat/sessions/update', {
        method: 'POST',
        body: JSON.stringify({
          session_id: selectedSessionId,
          status,
          eta_minutes_estimate: etaValue,
        }),
      });
      await refresh();
    });

    newProjectSession.addEventListener('click', async () => {
      const projectId = selectedProjectForNewChat();
      selectedProject = normalizeProject(projectId);
      localStorage.setItem(STORAGE_PROJECT_KEY, selectedProject);
      selectedSessionId = '';
      localStorage.setItem(STORAGE_SESSION_KEY, '');
      await ensureSession(selectedProject);
      await refresh();
    });

    saveChatMode.addEventListener('click', async () => {
      if (!selectedSessionId) return;
      await api('/api/chat/sessions/update', {
        method: 'POST',
        body: JSON.stringify({
          session_id: selectedSessionId,
          chat_mode: chatModeEl.value || 'general',
          skill: chatSkillEl.value.trim(),
        }),
      });
      await refresh();
    });

    projectSelectEl.addEventListener('change', () => {
      selectedProject = normalizeProject(projectSelectEl.value);
      localStorage.setItem(STORAGE_PROJECT_KEY, selectedProject);
      selectedSessionId = '';
      localStorage.setItem(STORAGE_SESSION_KEY, '');
      refresh();
    });

    customProjectEl.addEventListener('keydown', event => {
      if (event.key !== 'Enter') return;
      event.preventDefault();
      selectedProject = normalizeProject(selectedProjectForNewChat());
      localStorage.setItem(STORAGE_PROJECT_KEY, selectedProject);
      selectedSessionId = '';
      localStorage.setItem(STORAGE_SESSION_KEY, '');
      refresh();
    });

    recomputeProjectEta.addEventListener('click', async () => {
      await api('/api/chat/sessions/recompute_eta', {
        method: 'POST',
        body: JSON.stringify({project_id: selectedProject === 'all' ? '' : selectedProject}),
      });
      await refresh();
    });

    scrollDownBtn.addEventListener('click', () => {
      followTail = true;
      messagesEl.scrollTop = messagesEl.scrollHeight;
    });

    messagesEl.addEventListener('scroll', () => {
      if (!isNearBottom()) {
        followTail = false;
      }
    });

    refresh();
    setInterval(refresh, 2500);
  </script>
</body>
</html>
"""


def read_json_body(handler: BaseHTTPRequestHandler, max_bytes: int = 65536) -> dict[str, Any]:
    length = int(handler.headers.get("Content-Length") or "0")
    if length <= 0:
        return {}
    if length > max_bytes:
        raise ValueError("request body is too large")
    data = json.loads(handler.rfile.read(length).decode("utf-8"))
    if not isinstance(data, dict):
        raise ValueError("JSON object expected")
    return data


def project_value(project_id: Any) -> str:
    if not project_id:
        return ""
    return str(project_id).strip()


def parse_project(project_id: str | None) -> str:
    if not project_id:
        return ""
    value = str(project_id).strip()
    return value


def project_filter(query: dict[str, list[str]]) -> str | None:
    candidate = query.get("project_id", [""])[0]
    return parse_project(str(candidate or ""))


def is_system_session(session: dict[str, Any]) -> bool:
    channel = str(session.get("channel") or "").strip().lower()
    mode = str(session.get("chat_mode") or "").strip().lower()
    return channel in {"automation-debug", "system", "monitor"} or mode == "automation_debug"


def do_list_state(runtime_root: Path, query: dict[str, list[str]]) -> dict[str, Any]:
    project_id = project_filter(query)
    sessions = remote_chat_bus.list_sessions(runtime_root, project_id=project_id or None)
    state = remote_chat_bus.list_state(runtime_root)
    state["sessions"] = sessions
    return state


def make_handler(runtime_root: Path, token: str | None = None) -> type[BaseHTTPRequestHandler]:
    class ChatHandler(BaseHTTPRequestHandler):
        server_version = "AiStudioRemoteChat/0.2"

        def log_message(self, format: str, *args: Any) -> None:
            return

        def authorized(self) -> bool:
            if not token:
                return True
            auth = self.headers.get("Authorization") or ""
            header_token = self.headers.get("X-Aistudio-Chat-Token") or ""
            return auth == f"Bearer {token}" or header_token == token

        def send_json(self, status: HTTPStatus, payload: dict[str, Any]) -> None:
            body = json.dumps(payload, ensure_ascii=False, indent=2).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def send_html(self, text: str) -> None:
            body = text.encode("utf-8")
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def reject_if_unauthorized(self) -> bool:
            if self.authorized():
                return False
            self.send_json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
            return True

        def do_GET(self) -> None:
            parsed = urlparse(self.path)
            if parsed.path in {"/", "/chat", "/chat/"} or parsed.path.startswith("/chat/"):
                self.send_html(INDEX_HTML)
                return
            if self.reject_if_unauthorized():
                return
            if parsed.path == "/api/chat/state":
                query = parse_qs(parsed.query)
                self.send_json(HTTPStatus.OK, do_list_state(runtime_root, query))
                return
            if parsed.path == "/api/chat/projects":
                data = remote_chat_bus.list_state(runtime_root)
                projects = set()
                for session in data.get("sessions", []):
                    if isinstance(session, dict) and not is_system_session(session):
                        projects.add(project_value(session.get("project_id")))
                items = [value for value in projects if value]
                items.sort()
                self.send_json(HTTPStatus.OK, {"schema_version": "1.0", "projects": ["all", *items]})
                return
            if parsed.path == "/api/chat/sessions":
                query = parse_qs(parsed.query)
                sessions = remote_chat_bus.list_sessions(runtime_root, project_id=project_filter(query))
                self.send_json(HTTPStatus.OK, {"schema_version": "1.0", "sessions": sessions})
                return
            if parsed.path == "/api/chat/tasks":
                query = parse_qs(parsed.query)
                tasks = remote_chat_bus.list_tasks(runtime_root, session_id=str(query.get("session_id", [""])[0]))
                self.send_json(HTTPStatus.OK, {"schema_version": "1.0", "tasks": tasks})
                return
            if parsed.path == "/api/chat/messages":
                query = parse_qs(parsed.query)
                session_id = str((query.get("session_id") or [""])[0])
                if not session_id:
                    self.send_json(HTTPStatus.BAD_REQUEST, {"error": "session_id_required"})
                    return
                messages = remote_chat_bus.messages_for_session(runtime_root, session_id)
                self.send_json(HTTPStatus.OK, {"schema_version": "1.0", "messages": messages})
                return
            self.send_json(HTTPStatus.NOT_FOUND, {"error": "not_found", "path": parsed.path})

        def do_POST(self) -> None:
            parsed = urlparse(self.path)
            if self.reject_if_unauthorized():
                return
            try:
                payload = read_json_body(self)
                if parsed.path == "/api/chat/sessions":
                    session = remote_chat_bus.get_or_create_session(
                        runtime_root,
                        channel=str(payload.get("channel") or "web"),
                        external_id=str(payload.get("external_id") or ""),
                        title=str(payload.get("title") or ""),
                        actor=str(payload.get("actor") or "web"),
                        project_id=parse_project(payload.get("project_id")),
                        status=str(payload.get("status") or "planning"),
                        eta_minutes_estimate=payload.get("eta_minutes_estimate") if isinstance(payload.get("eta_minutes_estimate"), (int, float, str)) else None,
                        chat_mode=str(payload.get("chat_mode") or "general"),
                        skill=str(payload.get("skill") or ""),
                    )
                    self.send_json(HTTPStatus.OK, {"schema_version": "1.0", "session": session})
                    return
                if parsed.path == "/api/chat/sessions/update":
                    updated = remote_chat_bus.update_session(
                        runtime_root,
                        session_id=str(payload.get("session_id") or ""),
                        title=str(payload.get("title")) if payload.get("title") is not None else None,
                        project_id=parse_project(payload.get("project_id")) if payload.get("project_id") is not None else None,
                        status=str(payload.get("status") or "planning") if payload.get("status") is not None else None,
                        eta_minutes_estimate=int(payload.get("eta_minutes_estimate"))
                        if payload.get("eta_minutes_estimate") is not None and str(payload.get("eta_minutes_estimate")).strip() != ""
                        else None,
                        chat_mode=str(payload.get("chat_mode")) if payload.get("chat_mode") is not None else None,
                        skill=str(payload.get("skill")) if payload.get("skill") is not None else None,
                    )
                    self.send_json(HTTPStatus.OK, {"schema_version": "1.0", "session": updated})
                    return
                if parsed.path == "/api/chat/sessions/recompute_eta":
                    updated = remote_chat_bus.recompute_project_eta(
                        runtime_root,
                        project_id=parse_project(payload.get("project_id")),
                    )
                    self.send_json(HTTPStatus.OK, {
                        "schema_version": "1.0",
                        "updated_sessions": updated,
                        "updated_count": len(updated),
                    })
                    return
                if parsed.path == "/api/chat/messages":
                    session_id = str(payload.get("session_id") or "").strip()
                    if not session_id:
                        self.send_json(HTTPStatus.BAD_REQUEST, {"error": "session_id_required"})
                        return
                    message = remote_chat_bus.add_user_message(
                        runtime_root,
                        session_id=session_id,
                        text=str(payload.get("text") or ""),
                        actor=str(payload.get("actor") or "web"),
                        metadata=payload.get("metadata") if isinstance(payload.get("metadata"), dict) else {},
                    )
                    self.send_json(HTTPStatus.ACCEPTED, {"schema_version": "1.0", "message": message})
                    return
                if parsed.path == "/api/chat/clarify":
                    session_id = str(payload.get("session_id") or "").strip()
                    if not session_id:
                        self.send_json(HTTPStatus.BAD_REQUEST, {"error": "session_id_required"})
                        return
                    message = remote_chat_bus.add_system_message(
                        runtime_root,
                        session_id=session_id,
                        text=str(payload.get("text") or ""),
                        actor=str(payload.get("actor") or "web"),
                        metadata={"kind": "clarification"},
                    )
                    self.send_json(HTTPStatus.ACCEPTED, {"schema_version": "1.0", "message": message})
                    return
                if parsed.path == "/api/chat/tasks":
                    created = remote_chat_bus.add_session_task(
                        runtime_root,
                        session_id=str(payload.get("session_id") or "").strip(),
                        text=str(payload.get("text") or ""),
                        status=str(payload.get("status") or "pending"),
                        eta_minutes=int(payload.get("eta_minutes")) if payload.get("eta_minutes") is not None and str(payload.get("eta_minutes")).strip() != "" else None,
                        order_index=int(payload.get("order_index")) if payload.get("order_index") is not None and str(payload.get("order_index")).strip() != "" else None,
                    )
                    self.send_json(HTTPStatus.ACCEPTED, {"schema_version": "1.0", "task": created})
                    return
                if parsed.path == "/api/chat/tasks/update":
                    task_id = str(payload.get("task_id") or "").strip()
                    if not task_id:
                        self.send_json(HTTPStatus.BAD_REQUEST, {"error": "task_id_required"})
                        return
                    updated = remote_chat_bus.update_session_task(
                        runtime_root,
                        task_id=task_id,
                        status=str(payload.get("status") or "pending") if payload.get("status") is not None else None,
                        text=str(payload.get("text") or "")
                        if payload.get("text") is not None and str(payload.get("text")).strip() != ""
                        else None,
                        eta_minutes=int(payload.get("eta_minutes")) if payload.get("eta_minutes") is not None and str(payload.get("eta_minutes")).strip() != "" else None,
                        order_index=int(payload.get("order_index")) if payload.get("order_index") is not None and str(payload.get("order_index")).strip() != "" else None,
                    )
                    self.send_json(HTTPStatus.OK, {"schema_version": "1.0", "task": updated})
                    return
                if parsed.path == "/api/chat/tasks/delete":
                    task_id = str(payload.get("task_id") or "").strip()
                    if not task_id:
                        self.send_json(HTTPStatus.BAD_REQUEST, {"error": "task_id_required"})
                        return
                    remote_chat_bus.delete_session_task(runtime_root, task_id=task_id)
                    self.send_json(HTTPStatus.OK, {"schema_version": "1.0"})
                    return
            except KeyError as exc:
                self.send_json(HTTPStatus.NOT_FOUND, {"error": "not_found", "id": str(exc)})
                return
            except Exception as exc:
                self.send_json(HTTPStatus.BAD_REQUEST, {"error": str(exc)})
                return
            self.send_json(HTTPStatus.NOT_FOUND, {"error": "not_found", "path": parsed.path})

    return ChatHandler


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime-root", default=os.environ.get("AISTUDIO_RUNTIME_ROOT", "runtime/agent-control"))
    parser.add_argument("--host", default=os.environ.get("AISTUDIO_CHAT_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("AISTUDIO_CHAT_PORT", "8095")))
    parser.add_argument("--token-env", default="AISTUDIO_CHAT_TOKEN")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    runtime_root = Path(args.runtime_root).expanduser()
    token = os.environ.get(args.token_env) or None
    handler = make_handler(runtime_root, token=token)
    server = ThreadingHTTPServer((args.host, args.port), handler)
    print(f"remote chat server listening on http://{args.host}:{args.port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        return 130
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
