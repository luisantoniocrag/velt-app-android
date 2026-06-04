export class AppError extends Error {
  constructor(
    public readonly statusCode: number,
    public readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = "AppError";
  }
}

export const badRequest = (msg: string, code = "bad_request") => new AppError(400, code, msg);
export const notFound = (msg: string, code = "not_found") => new AppError(404, code, msg);
export const conflict = (msg: string, code = "conflict") => new AppError(409, code, msg);
export const internal = (msg: string, code = "internal_error") => new AppError(500, code, msg);
