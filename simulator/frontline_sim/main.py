import logging
import time

import httpx

from .api import API
from .bots import Bots
from .config import Config
from .engine import Engine


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    logging.getLogger("httpx").setLevel(logging.WARNING)
    try:
        config = Config.from_env()
        with httpx.Client(base_url=config.base_url, timeout=10, trust_env=False) as client:
            api = API(client)
            bots = Bots(api, config)
            engine = Engine(config, api, bots)
            while True:
                started = time.monotonic()
                if bots.connect():
                    engine.tick()
                time.sleep(max(0, config.tick_seconds - (time.monotonic() - started)))
    except ValueError as error:
        logging.error("%s", error)
        return 1
    except KeyboardInterrupt:
        return 0


if __name__ == "__main__":
    raise SystemExit(main())
