"""Measure average centipawn loss against known playing strength.

The app estimates a player's level from their mean capped centipawn loss, but the band
boundaries were guessed. This measures them instead: Stockfish limited to a known UCI_Elo
stands in for a player of that strength, plays the same 12-move game the app's level check
plays against the same Club-level opponent, and every one of its moves is judged at full
strength with the same depth and cap the app uses.

The output is a loss-vs-rating curve the band boundaries can be read off.

Limits worth stating: UCI_Elo is Stockfish's own approximation of a rating scale, not a human
one, and an engine held to 1500 makes different mistakes than a 1500-rated human — fewer
outright tactical oversights, more mild positional drift. So this calibrates the app's metric
against a defensible reference, not against ground truth.
"""
import json
import statistics
import subprocess
import sys
from concurrent.futures import ProcessPoolExecutor

# A Stockfish built for this host. Build one from the vendored source with:
#   cd app/src/main/cpp/stockfish && make -j build ARCH=apple-silicon
# (the networks must be alongside the sources, or pass EvalFile explicitly)
ENGINE = __import__("os").environ.get("STOCKFISH", "./stockfish")

# Mirrors the app exactly: GameViewModel.DEFAULT_ASSESSMENT_MOVES, OpponentLevel.ASSESSMENT
# (CLUB = 1750 Elo at depth 10), ChessEngine.DEFAULT_ANALYSIS_DEPTH, LevelCalibration.LOSS_CAP.
ASSESSMENT_MOVES = int(__import__("os").environ.get("MOVES", "30"))
OPPONENT_ELO = 1750
OPPONENT_DEPTH = 10
PLAYER_DEPTH = 10
ANALYSIS_DEPTH = 14
LOSS_CAP = 300

MATE_SCORE = 100_000
LEVELS = [1350, 1500, 1750, 2000, 2300, 2600, 2900]
GAMES_PER_LEVEL = int(__import__("os").environ.get("GAMES", "5"))


class Engine:
    def __init__(self, path):
        self.p = subprocess.Popen(
            [path], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            text=True, bufsize=1,
        )
        self.send("uci")
        self.wait("uciok")
        self.send("setoption name Threads value 1")
        self.send("setoption name Hash value 64")
        self.ready()

    def send(self, command):
        self.p.stdin.write(command + "\n")
        self.p.stdin.flush()

    def wait(self, token):
        while True:
            line = self.p.stdout.readline()
            if not line:
                raise RuntimeError("engine exited")
            if line.strip().startswith(token):
                return line.strip()

    def ready(self):
        self.send("isready")
        self.wait("readyok")

    def go(self, command):
        """Returns (bestmove, score) with the score from the side to move's point of view."""
        self.send(command)
        best, score, best_depth = None, None, -1
        while True:
            line = self.p.stdout.readline()
            if not line:
                raise RuntimeError("engine exited mid-search")
            line = line.strip()
            if line.startswith("info") and " score " in line:
                t = line.split()
                depth = int(t[t.index("depth") + 1]) if "depth" in t else 0
                i = t.index("score")
                kind, value = t[i + 1], int(t[i + 2])
                if depth >= best_depth:
                    best_depth = depth
                    if kind == "cp":
                        score = value
                    else:  # mate in `value` moves, negative when getting mated
                        score = (MATE_SCORE - (2 * value - 1)) if value > 0 \
                            else (-MATE_SCORE + 2 * abs(value))
            elif line.startswith("bestmove"):
                parts = line.split()
                if len(parts) > 1 and parts[1] != "(none)":
                    best = parts[1]
                return best, score

    def limit_to(self, elo):
        self.send("setoption name UCI_LimitStrength value true")
        self.send(f"setoption name UCI_Elo value {elo}")
        self.ready()

    def unlimited(self):
        self.send("setoption name UCI_LimitStrength value false")
        self.ready()

    def close(self):
        self.send("quit")
        self.p.wait(timeout=10)


def play_one_game(engine, player_elo):
    """Plays the app's level-check game and returns the per-move capped losses."""
    engine.send("ucinewgame")
    engine.ready()
    history, losses = [], []

    for _ in range(ASSESSMENT_MOVES):
        position = "position startpos" + (" moves " + " ".join(history) if history else "")

        engine.limit_to(player_elo)
        engine.send(position)
        played, _ = engine.go(f"go depth {PLAYER_DEPTH}")
        if played is None:
            break

        # Judge at full strength from the same position, so both scores share a scale.
        engine.unlimited()
        engine.send(position)
        _, best_score = engine.go(f"go depth {ANALYSIS_DEPTH}")
        engine.send(position)
        _, played_score = engine.go(f"go depth {ANALYSIS_DEPTH} searchmoves {played}")
        if best_score is not None and played_score is not None:
            losses.append(min(max(best_score - played_score, 0), LOSS_CAP))

        history.append(played)

        engine.limit_to(OPPONENT_ELO)
        engine.send("position startpos moves " + " ".join(history))
        reply, _ = engine.go(f"go depth {OPPONENT_DEPTH}")
        if reply is None:
            break
        history.append(reply)

    return losses


def measure_level(elo):
    engine = Engine(ENGINE)
    try:
        per_game = []
        for _ in range(GAMES_PER_LEVEL):
            losses = play_one_game(engine, elo)
            if losses:
                per_game.append(sum(losses) / len(losses))
        return {
            "elo": elo,
            "games": len(per_game),
            "mean_loss": round(statistics.mean(per_game), 1) if per_game else None,
            "median_loss": round(statistics.median(per_game), 1) if per_game else None,
            "stdev": round(statistics.stdev(per_game), 1) if len(per_game) > 1 else 0.0,
            "per_game": [round(x, 1) for x in per_game],
        }
    finally:
        engine.close()


if __name__ == "__main__":
    with ProcessPoolExecutor(max_workers=len(LEVELS)) as pool:
        results = list(pool.map(measure_level, LEVELS))
    results.sort(key=lambda r: r["elo"])
    print(json.dumps(results, indent=2))
    print("\nElo   mean loss (cp)   median   stdev   games", file=sys.stderr)
    for r in results:
        print(f"{r['elo']:<6}{str(r['mean_loss']):<17}{str(r['median_loss']):<9}"
              f"{str(r['stdev']):<8}{r['games']}", file=sys.stderr)
