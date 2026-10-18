// Frases motivacionais da verificação do meio da atividade: a voz fala uma, a pessoa repete. Curtas, mas
// não tanto quanto "I want to be rich"; sem contrações ("I am", não "I'm"), pro reconhecedor conferir
// melhor. Pra mudar, é só mexer na lista: todo dia o app sorteia dela quantas o dono pediu (frasesDoDia; de começo, 5).
package com.implacavel.alarme

import java.time.LocalDate
import kotlin.random.Random

val FRASES_MOTIVACIONAIS = listOf(
    "I am fully awake and ready to make money",
    "Today I choose discipline over comfort",
    "My mind is clear and my body is ready",
    "I get up early because my goals matter",
    "Every morning I become a better version of myself",
    "I am focused, full of energy and unstoppable today",
    "Hard work today builds my freedom tomorrow",
    "I wake up with purpose and gratitude",
    "I am the boss of my time and my day",
    "Success starts the moment I leave my bed",
    "I am awake, alert and full of energy",
    "My future self is thanking me right now",
    "I keep my promises, starting with this alarm",
    "Today I will do the work that others avoid",
    "I am strong, healthy and ready to win",
    "Money comes to people who show up early",
    "I turn every morning into my advantage",
    "I do not wait for motivation, I create it",
    "Small wins every morning build a great life",
    "I am grateful for this brand new day",
    "My energy is high and my focus is sharp",
    "I am building the life I want one day at a time",
    "Discipline is my superpower every single morning",
    "Nothing can stop me when I am fully awake",
    "I am ready to make today really count",
    "I rise early and I rise strong",
    "I take action now and not tomorrow",
    "Great things happen when I get out of bed",
    "I control my mornings so I control my life",
    "I am wide awake and ready for real work",
)

/** As [quantas] frases do [dia] ([Tempos.frases]), sorteadas da lista: sempre as mesmas no mesmo dia, outras no dia seguinte. */
fun frasesDoDia(dia: LocalDate, quantas: Int = TEMPOS_PADRAO.frases): List<String> =
    FRASES_MOTIVACIONAIS.shuffled(Random(dia.toEpochDay())).take(quantas.coerceIn(0, FRASES_MOTIVACIONAIS.size))
