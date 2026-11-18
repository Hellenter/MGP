package ru.mgp.stats;

/** Счётчики игрока в одном режиме (или сумма по всем). */
public final class Counts {
    public int played, wins, kills, deaths;

    public void add(Counts o) {
        played += o.played;
        wins += o.wins;
        kills += o.kills;
        deaths += o.deaths;
    }

    public int losses() { return Math.max(0, played - wins); }

    /** K/D: убийства на смерть (без смертей — просто убийства). */
    public double kd() { return deaths == 0 ? kills : (double) kills / deaths; }

    /** Доля побед в процентах. */
    public int winRate() { return played == 0 ? 0 : (int) Math.round(wins * 100.0 / played); }

    public double get(Stat s) {
        return switch (s) {
            case WINS -> wins;
            case KILLS -> kills;
            case DEATHS -> deaths;
            case PLAYED -> played;
            case KD -> kd();
        };
    }

    public boolean empty() { return played == 0 && wins == 0 && kills == 0 && deaths == 0; }
}
