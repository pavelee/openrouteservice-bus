# Pochodzenie fikstury dostępu do parkingu

Źródło: surowy PBF aktywnego grafu z 22 października, SHA256 `3f7fae86900ca9a141418c3b7234abeb72016f4b99ce8de5e8d0f6c760a68c24`.

`terminal-parking-access.xml` zachowuje 109 oryginalnych węzłów i 28 oryginalnych dróg. Zawiera kompletne komponenty parkingowe oraz wszystkie drogi ze wspólnymi węzłami. Nie ma nowych dróg ani interwencji.

| Przypadek | Drogi parkingowe | Oczekiwana topologia |
| --- | --- | --- |
| Palmiry | `147698371`, `147698373`, `1339003166` | Jedna brama, dostępna wewnętrzna pętla |
| Ursus | `248674689` do `248674693` | Pięć osobnych łączników po dwie bramy, bez nowego dopuszczenia |

Testy Python wykonują obie transformacje. Fikstury Java w `ors-engine/src/test/resources/route-quality/` zachowują odpowiednie surowe tagi oraz skompilowane tagi Palmiry. Enkoder ma odrzucić nieoznaczone łączniki, zachowując ograniczenia fizyczne, dostęp, jednokierunkowość i brak preferencji autobusowej.

Odtworzenie ekstrakcji i pomiary całego grafu są w katalogu zadania firstmate `data/traska-rq-pilot-naprawy-etap2/`.
