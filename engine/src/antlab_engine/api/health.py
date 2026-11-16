from typing import Literal

from fastapi import APIRouter
from pydantic import BaseModel

from ..errors import Problem

router = APIRouter()


class Health(BaseModel):
    status: Literal["ok"]


@router.get(
    "/healthz",
    response_model=Health,
    operation_id="healthz",
    responses={500: {"model": Problem, "description": "Internal error"}},
)
async def healthz() -> Health:
    return Health(status="ok")
