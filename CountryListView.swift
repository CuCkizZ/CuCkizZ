import SwiftUI
import NetworkExtension
import Foundation
import PDFKit
import UIKit

struct CountryListView: View {
    @StateObject var viewModel: AppViewModel
    @Environment(\.dismiss) var dismiss
    
    private let hapticFeedback = UIImpactFeedbackGenerator(style: .medium)
    
    var body: some View {
        ZStack {
            Color.gray.opacity(0.1).ignoresSafeArea()
            
            List {
                ForEach(viewModel.regions) { region in
                    Button {
                        hapticFeedback.impactOccurred()
                        withAnimation(.spring(response: 0.4, dampingFraction: 0.7)) {
                            viewModel.connect(to: region)
                        }
                        
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                            dismiss()
                        }
                    } label: {
                        HStack(spacing: 12) {
                            PDFImageView(urlString: region.urlPdf)
                                .frame(width: 20, height: 14)
                            
                            Text(region.name)
                                .font(.system(size: 17, weight: .regular))
                                .foregroundColor(.primary)
                            
                            Spacer()
                            
                            if viewModel.connectedRegionId == region.region_id {
                                Text("Connected")
                                    .font(.system(size: 12, weight: .regular))
                                    .foregroundColor(Color(hex: "004E4E"))
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 4)
                                    .background(
                                        Capsule()
                                            .fill(Color(hex: "DAF6F2"))
                                    )
                                    .transition(.scale.combined(with: .opacity))
                            }
                            
                            Image(region.signalStrength)
                                .resizable()
                                .scaledToFit()
                                .frame(width: 24, height: 24)
                        }
                        .padding(.vertical, 12)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
                    .listRowSeparatorLeading(.constant(6))
                    .listRowBackground(Color.white.opacity(0.9))
                }
            }
            .refreshable {
                await viewModel.refreshRegions()
            }
            
            if viewModel.regions.isEmpty {
                VStack {
                    ProgressView()
                        .scaleEffect(1.5)
                    Text("Загрузка серверов...")
                        .font(.system(size: 16))
                        .foregroundColor(.gray)
                        .padding(.top, 8)
                }
            }
        }
        .navigationTitle("Серверы")
    }
}

#Preview {
    CountryListView(viewModel: AppViewModel())
}
