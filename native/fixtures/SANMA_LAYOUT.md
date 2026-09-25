The deployed three-player Mortal v4 model uses 775 channels over the 34 tile axis. These offsets were reconstructed with the repository's actual prebuilt CPython 3.12 libriichi3p library. CI requires full observation parity before enabling this rules variant. The reconstruction does not assume the public native v5 encoder is compatible.

| Rows (zero based) | Meaning |
| --- | --- |
| 0–3, 4–6 | Hand counts and red fives |
| 7–12 | Three scores, each normalized by 105,000 and 40,000 |
| 13–15, 16–18 | Rank and round within the wind |
| 19–20 | Honba and deposits |
| 21–23 | Round wind, seat wind, progress retaining `(wind * 4 + round) / 7` |
| 24–30 | Dora indicators |
| 31–127 | Own discards and decay |
| 128–322, 323–517 | Each opponent's discards and decay |
| 518 | Tiles left, retaining denominator 69 |
| 519–522, 523 | Dora owned (four channels, including the unused fourth) and unseen dora |
| 524–544 | Discard overviews |
| 545–604, 605–607 | Open melds and closed kans |
| 608 | Seen tiles; unavailable 2m–8m have value 1 |
| 609–614, 615–620 | Last hand discard and riichi discard of each opponent |
| 621–622, 623–624 | Opponent riichi declared and accepted |
| 625–626, 627–633 | Waits, furiten, and shanten |
| 634–635 | Own accepted riichi and kan selection |
| 636–638 | Tile available for a call |
| 639–643 | Legal discard, shanten-preserving/reducing discard, unconditional tenpai, own riichi declaration |
| 644–651 | Riichi, pon, daiminkan, kita, ankan, kakan, win, abortive draw |
| 652–653 | Expected value normalized by 105,000 and 40,000 |
| 654–721 | Required tiles for discard candidates |
| 722–723 | Most required tiles and draw-only required tiles |
| 724–740, 741–757, 758–774 | Single-player tenpai probability, win probability, relative expected value |

Action indices remain separate from observation rows: 0–36 discard/kan tile choice, 37 riichi, 38 pon, 39 kan, 40 kita, 41 win, 42 abortive draw, 43 pass. The single-player draw horizon retains division by four, as in the historical binary. These historical normalizations are part of the checkpoint's input contract and must not be made more intuitive during a port.

Call history padding also retains a four-seat absolute traversal before applying the three-seat relative mapping. This makes `pon(actor=0, target=2)` add a blank row to player 0's history and age player 2's just-called tile by one row. The same behavior is verified for every absolute caller and target pair.
