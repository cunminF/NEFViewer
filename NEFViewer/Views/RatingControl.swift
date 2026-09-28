import SwiftUI

struct RatingControl: View {
    let rating: Int
    var onRate: ((Int) -> Void)? = nil

    var body: some View {
        HStack(spacing: 3) {
            ForEach(1...5, id: \.self) { star in
                Image(systemName: star <= rating ? "star.fill" : "star")
                    .foregroundStyle(star <= rating ? Color.yellow : Color.secondary)
                    .contentShape(Rectangle())
                    .onTapGesture {
                        // 点击当前最高星级 = 清除评分（Lightroom 习惯）
                        onRate?(star == rating ? 0 : star)
                    }
                    .disabled(onRate == nil)
            }
        }
        .imageScale(.medium)
    }
}
