import type { NextFunction, Request, Response } from 'express';
import { isAxiosError } from 'axios';
import { v4 as uuidv4 } from 'uuid';

export type ProblemDetails = {
  type?: string;
  title: string;
  status: number;
  detail?: string;
  instance: string;
  [key: string]: unknown;
};

const SENSITIVE_FIELD_PATTERN = /stack|trace|exception|cause/i;

const isRecord = (value: unknown): value is Record<string, unknown> => {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
};

const sanitizeClientProblem = (
  data: Record<string, unknown>,
  status: number,
  instance: string,
): ProblemDetails => {
  const problem: ProblemDetails = {
    type: typeof data.type === 'string' ? data.type : undefined,
    title: typeof data.title === 'string' ? data.title : 'Bad Request',
    status,
    detail:
      typeof data.detail === 'string'
        ? data.detail
        : 'The request could not be processed.',
    instance,
  };

  for (const [key, value] of Object.entries(data)) {
    if (key === 'type' || key === 'title' || key === 'status' || key === 'detail' || key === 'instance') {
      continue;
    }
    if (SENSITIVE_FIELD_PATTERN.test(key)) {
      continue;
    }
    problem[key] = value;
  }

  return problem;
};

export const sanitizeError = (err: unknown): ProblemDetails => {
  const instance = `/problems/${uuidv4()}`;

  if (isAxiosError(err)) {
    const { response } = err;

    if (response) {
      const { status, data } = response;

      if (status >= 400 && status < 500) {
        if (isRecord(data)) {
          return sanitizeClientProblem(data, status, instance);
        }

        return {
          type: undefined,
          title: 'Bad Request',
          status,
          detail: 'The request could not be processed.',
          instance,
        };
      }

      return {
        type: undefined,
        title: 'Upstream Service Error',
        status,
        detail: 'The upstream service encountered an error.',
        instance,
      };
    }

    return {
      type: undefined,
      title: 'Bad Gateway',
      status: 502,
      detail: 'Unable to reach the upstream service.',
      instance,
    };
  }

  return {
    type: undefined,
    title: 'Internal Server Error',
    status: 500,
    detail: 'An internal error occurred.',
    instance,
  };
};

type RequestWithLogger = Request & {
  log?: {
    error: (obj: unknown, msg?: string, ...args: unknown[]) => void;
  };
};

export const errorHandler = (err: unknown, req: Request, res: Response, next: NextFunction) => {
  if (res.headersSent) {
    return next(err);
  }

  const problem = sanitizeError(err);

  const requestWithLogger = req as RequestWithLogger;
  requestWithLogger.log?.error({ err, instance: problem.instance }, 'Unhandled gateway error');

  res
    .status(problem.status ?? 500)
    .type('application/problem+json')
    .json(problem);
};
